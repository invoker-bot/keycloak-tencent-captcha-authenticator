#!/usr/bin/env python3
"""Safely prepare the Keycloak runtime secret on a Linux host."""

from __future__ import annotations

import os
import stat
import sys
import uuid
from pathlib import Path

EXPECTED_UID = 1000
EXPECTED_GID = 0
EXPECTED_KEYS = {
    "CAPTCHA_APP_ID",
    "CAPTCHA_APP_SECRET_KEY",
    "TENCENT_SECRET_ID",
    "TENCENT_SECRET_KEY",
}
MAX_SOURCE_BYTES = 64 * 1024
SECRET_DIRECTORY = "secrets"
SECRET_FILENAME = "tencent-captcha.env"


class SecretPreparationError(RuntimeError):
    """A bounded preparation failure that never contains secret data."""


def _identity(metadata: os.stat_result) -> tuple[int, int]:
    return metadata.st_dev, metadata.st_ino


def _open_flags(*, directory: bool = False, write: bool = False) -> int:
    flags = os.O_CLOEXEC | os.O_NOFOLLOW
    flags |= os.O_WRONLY if write else os.O_RDONLY
    if directory:
        flags |= os.O_DIRECTORY
    return flags


def _read_source(source: Path) -> bytes:
    try:
        before = os.lstat(source)
        if not stat.S_ISREG(before.st_mode):
            raise SecretPreparationError("secret-source-not-regular")
        source_fd = os.open(source, _open_flags())
    except (OSError, ValueError):
        raise SecretPreparationError("secret-source-unavailable") from None

    try:
        opened = os.fstat(source_fd)
        if not stat.S_ISREG(opened.st_mode) or _identity(opened) != _identity(before):
            raise SecretPreparationError("secret-source-identity-changed")
        chunks: list[bytes] = []
        remaining = MAX_SOURCE_BYTES + 1
        while remaining:
            chunk = os.read(source_fd, min(8192, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        content = b"".join(chunks)
        if len(content) > MAX_SOURCE_BYTES:
            raise SecretPreparationError("secret-source-too-large")
        after = os.lstat(source)
        if _identity(after) != _identity(opened):
            raise SecretPreparationError("secret-source-identity-changed")
        return content
    except OSError:
        raise SecretPreparationError("secret-source-read-failed") from None
    finally:
        os.close(source_fd)


def _validate_source(content: bytes) -> None:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError:
        raise SecretPreparationError("secret-source-invalid-encoding") from None
    if "\r" in text:
        raise SecretPreparationError("secret-source-invalid-lines")
    lines = text.splitlines()
    if len(lines) != 4:
        raise SecretPreparationError("secret-source-invalid-schema")
    values: dict[str, str] = {}
    for line in lines:
        key, separator, value = line.partition("=")
        if not separator or key in values or key not in EXPECTED_KEYS or not value.strip():
            raise SecretPreparationError("secret-source-invalid-schema")
        values[key] = value
    if set(values) != EXPECTED_KEYS:
        raise SecretPreparationError("secret-source-invalid-schema")


def _path_metadata(name: str, directory_fd: int) -> os.stat_result | None:
    try:
        return os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    except FileNotFoundError:
        return None
    except OSError:
        raise SecretPreparationError("secret-path-inspection-failed") from None


def _require_same_target(before: os.stat_result | None, current: os.stat_result | None) -> None:
    if before is None:
        if current is not None:
            raise SecretPreparationError("secret-target-identity-changed")
        return
    if current is None or _identity(current) != _identity(before):
        raise SecretPreparationError("secret-target-identity-changed")


def _verify_directory_identity(script_fd: int, directory_fd: int) -> None:
    current = _path_metadata(SECRET_DIRECTORY, script_fd)
    opened = os.fstat(directory_fd)
    if current is None or not stat.S_ISDIR(current.st_mode) or _identity(current) != _identity(opened):
        raise SecretPreparationError("secret-directory-identity-changed")


def _prepare_with_directory_fd(script_fd: int, directory_fd: int, content: bytes) -> None:
    opened_directory = os.fstat(directory_fd)
    if not stat.S_ISDIR(opened_directory.st_mode):
        raise SecretPreparationError("secret-directory-not-regular")
    _verify_directory_identity(script_fd, directory_fd)
    os.fchmod(directory_fd, 0o700)

    original_target = _path_metadata(SECRET_FILENAME, directory_fd)
    if original_target is not None and not stat.S_ISREG(original_target.st_mode):
        raise SecretPreparationError("secret-target-not-regular")

    temporary_name = f".{SECRET_FILENAME}.{uuid.uuid4().hex}.tmp"
    temporary_fd = -1
    temporary_exists = False
    try:
        temporary_fd = os.open(
            temporary_name,
            _open_flags(write=True) | os.O_CREAT | os.O_EXCL,
            0o600,
            dir_fd=directory_fd,
        )
        temporary_exists = True
        view = memoryview(content)
        while view:
            written = os.write(temporary_fd, view)
            if written <= 0:
                raise SecretPreparationError("secret-temporary-write-failed")
            view = view[written:]
        os.fchown(temporary_fd, EXPECTED_UID, EXPECTED_GID)
        os.fchmod(temporary_fd, 0o400)
        os.fsync(temporary_fd)
        installed_identity = _identity(os.fstat(temporary_fd))

        _verify_directory_identity(script_fd, directory_fd)
        _require_same_target(original_target, _path_metadata(SECRET_FILENAME, directory_fd))
        os.replace(temporary_name, SECRET_FILENAME, src_dir_fd=directory_fd, dst_dir_fd=directory_fd)
        temporary_exists = False

        installed_fd = os.open(SECRET_FILENAME, _open_flags(), dir_fd=directory_fd)
        try:
            installed = os.fstat(installed_fd)
            if _identity(installed) != installed_identity:
                raise SecretPreparationError("secret-target-identity-changed")
            if (
                installed.st_uid != EXPECTED_UID
                or installed.st_gid != EXPECTED_GID
                or stat.S_IMODE(installed.st_mode) != 0o400
            ):
                raise SecretPreparationError("secret-target-metadata-invalid")
        finally:
            os.close(installed_fd)
        _verify_directory_identity(script_fd, directory_fd)
        if stat.S_IMODE(os.fstat(directory_fd).st_mode) != 0o700:
            raise SecretPreparationError("secret-directory-mode-invalid")
    except SecretPreparationError:
        raise
    except OSError:
        raise SecretPreparationError("secret-install-failed") from None
    finally:
        if temporary_fd >= 0:
            os.close(temporary_fd)
        if temporary_exists:
            try:
                os.unlink(temporary_name, dir_fd=directory_fd)
            except OSError:
                pass


def prepare_secret(source: Path, *, script_dir: Path, require_linux: bool = True) -> None:
    if require_linux and not sys.platform.startswith("linux"):
        raise SecretPreparationError("linux-host-required")
    if require_linux and os.geteuid() != 0:
        raise SecretPreparationError("root-required")

    content = _read_source(Path(source))
    _validate_source(content)
    resolved_script_dir = Path(script_dir).resolve(strict=True)
    try:
        script_fd = os.open(resolved_script_dir, _open_flags(directory=True))
    except OSError:
        raise SecretPreparationError("example-directory-unavailable") from None
    try:
        try:
            os.mkdir(SECRET_DIRECTORY, 0o700, dir_fd=script_fd)
        except FileExistsError:
            pass
        directory_metadata = _path_metadata(SECRET_DIRECTORY, script_fd)
        if directory_metadata is None or not stat.S_ISDIR(directory_metadata.st_mode):
            raise SecretPreparationError("secret-directory-not-regular")
        try:
            directory_fd = os.open(SECRET_DIRECTORY, _open_flags(directory=True), dir_fd=script_fd)
        except OSError:
            raise SecretPreparationError("secret-directory-unavailable") from None
        try:
            _prepare_with_directory_fd(script_fd, directory_fd, content)
        finally:
            os.close(directory_fd)
    finally:
        os.close(script_fd)


def main(argv: list[str]) -> int:
    script_dir = Path(__file__).resolve().parent
    source = Path(argv[1]) if len(argv) > 1 else script_dir / "secrets/tencent-captcha.env.example"
    if len(argv) > 2:
        print("Secret preparation failed: invalid arguments.", file=sys.stderr)
        return 2
    try:
        prepare_secret(source, script_dir=script_dir)
    except SecretPreparationError as error:
        print(f"Secret preparation failed: {error}.", file=sys.stderr)
        return 1
    print("Prepared restricted runtime secret file.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
