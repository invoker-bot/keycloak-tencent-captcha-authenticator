#!/usr/bin/env python3
"""Build and smoke-test the public Docker Compose example on Linux."""

from __future__ import annotations

import os
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
EXAMPLE_DIRECTORY = PROJECT_ROOT / "examples/docker-compose"
COMPOSE_FILE = EXAMPLE_DIRECTORY / "compose.yaml"
PREPARE_SECRET = EXAMPLE_DIRECTORY / "prepare-secret.sh"
SECRET_DIRECTORY = EXAMPLE_DIRECTORY / "secrets"
SECRET_FILE = SECRET_DIRECTORY / "tencent-captcha.env"
JAR_FILE = PROJECT_ROOT / "target/keycloak-tencent-captcha-authenticator-0.1.0.jar"
EXPECTED_SECRET_UID = 1000
EXPECTED_SECRET_GID = 0
WORK_BUDGET_SECONDS = 420
CLEANUP_BUDGET_SECONDS = 60
COMMAND_BUDGET_SECONDS = 300
READY_BUDGET_SECONDS = 120
SYNTHETIC_SECRET = (
    "CAPTCHA_APP_ID=123456789\n"
    "CAPTCHA_APP_SECRET_KEY=synthetic-app-key\n"
    "TENCENT_SECRET_ID=synthetic-api-id\n"
    "TENCENT_SECRET_KEY=synthetic-api-key\n"
)


class PublicExampleError(RuntimeError):
    """A bounded public-example acceptance failure without command output."""


class Deadline:
    def __init__(self, seconds: int):
        self.expires_at = time.monotonic() + seconds

    def timeout(self, maximum: int) -> float:
        remaining = self.expires_at - time.monotonic()
        if remaining <= 0:
            raise PublicExampleError("public-example-deadline-exceeded")
        return max(0.1, min(float(maximum), remaining))


def _run(
    command: list[str],
    *,
    deadline: Deadline,
    env: dict[str, str],
    label: str,
    cwd: Path = PROJECT_ROOT,
    maximum: int = COMMAND_BUDGET_SECONDS,
) -> subprocess.CompletedProcess[str]:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            capture_output=True,
            text=True,
            timeout=deadline.timeout(maximum),
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise PublicExampleError(f"{label}-command-unavailable-or-timed-out") from None
    if completed.returncode != 0:
        raise PublicExampleError(f"{label}-exit-{completed.returncode}")
    return completed


def _require_linux() -> None:
    if not sys.platform.startswith("linux"):
        raise PublicExampleError("linux-host-required")


def _require_secret_target_absent(target: Path) -> None:
    try:
        os.lstat(target)
    except FileNotFoundError:
        return
    except OSError:
        raise PublicExampleError("public-example-secret-preflight-failed") from None
    raise PublicExampleError("public-example-secret-already-exists")


def _available_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind(("127.0.0.1", 0))
        return int(server.getsockname()[1])


def _prepare_secret(source: Path, deadline: Deadline, env: dict[str, str]) -> Path:
    command = [str(PREPARE_SECRET), str(source)]
    if os.geteuid() != 0:
        command = ["sudo", "--non-interactive", *command]
    _run(command, deadline=deadline, env=env, label="prepare-secret", cwd=EXAMPLE_DIRECTORY, maximum=30)
    return SECRET_FILE


def _verify_secret_metadata(directory: Path, target: Path) -> None:
    directory_metadata = directory.lstat()
    target_metadata = target.lstat()
    if not stat.S_ISDIR(directory_metadata.st_mode) or directory.is_symlink():
        raise PublicExampleError("secret-directory-not-regular")
    if stat.S_IMODE(directory_metadata.st_mode) != 0o700:
        raise PublicExampleError("secret-directory-mode-invalid")
    if not stat.S_ISREG(target_metadata.st_mode) or target.is_symlink():
        raise PublicExampleError("secret-file-not-regular")
    if (
        target_metadata.st_uid != EXPECTED_SECRET_UID
        or target_metadata.st_gid != EXPECTED_SECRET_GID
        or stat.S_IMODE(target_metadata.st_mode) != 0o400
    ):
        raise PublicExampleError("secret-file-metadata-invalid")


def _wait_ready(port: int, deadline: Deadline) -> None:
    endpoint = f"http://127.0.0.1:{port}/realms/master/.well-known/openid-configuration"
    expires_at = time.monotonic() + deadline.timeout(READY_BUDGET_SECONDS)
    while time.monotonic() < expires_at:
        try:
            with urllib.request.urlopen(endpoint, timeout=min(3.0, deadline.timeout(3))) as response:
                if response.status == 200:
                    response.read(1)
                    return
        except (TimeoutError, urllib.error.URLError, OSError):
            pass
        time.sleep(min(1.0, deadline.timeout(1)))
    raise PublicExampleError("public-example-readiness-timeout")


def _remove_secret(target: Path) -> None:
    try:
        target.unlink(missing_ok=True)
    except OSError:
        try:
            subprocess.run(
                ["sudo", "--non-interactive", "rm", "--force", str(target)],
                capture_output=True,
                text=True,
                timeout=10,
                check=True,
            )
        except (OSError, subprocess.SubprocessError):
            raise PublicExampleError("secret-cleanup-failed") from None


def run() -> None:
    _require_linux()
    _require_secret_target_absent(SECRET_FILE)
    if not JAR_FILE.is_file():
        raise PublicExampleError("provider-jar-missing-run-maven-verify-first")
    deadline = Deadline(WORK_BUDGET_SECONDS)
    environment = os.environ.copy()
    port = _available_loopback_port()
    environment["KEYCLOAK_HTTP_PORT"] = str(port)
    project_name = f"tencent-captcha-public-example-{os.getpid()}"
    compose = ["docker", "compose", "--project-name", project_name, "-f", str(COMPOSE_FILE)]
    primary_failure: Exception | None = None
    cleanup_ok = True
    prepared_target: Path | None = None

    with tempfile.TemporaryDirectory(prefix="tencent-captcha-public-example-") as temporary:
        source = Path(temporary) / "synthetic.env"
        source.write_text(SYNTHETIC_SECRET, encoding="utf-8", newline="\n")
        try:
            prepared_target = _prepare_secret(source, deadline, environment)
            _verify_secret_metadata(SECRET_DIRECTORY, prepared_target)
            _run([*compose, "config", "--quiet"], deadline=deadline, env=environment, label="compose-config")
            _run(
                [*compose, "up", "--build", "--detach", "--force-recreate"],
                deadline=deadline,
                env=environment,
                label="compose-up",
            )
            _wait_ready(port, deadline)
        except Exception as error:  # converted below to a bounded label
            primary_failure = error
        finally:
            try:
                cleanup_deadline = Deadline(CLEANUP_BUDGET_SECONDS)
                _run(
                    [*compose, "down", "--volumes", "--remove-orphans", "--timeout", "15"],
                    deadline=cleanup_deadline,
                    env=environment,
                    label="compose-down",
                    maximum=CLEANUP_BUDGET_SECONDS,
                )
                remaining = _run(
                    [*compose, "ps", "--all", "--quiet"],
                    deadline=cleanup_deadline,
                    env=environment,
                    label="compose-residue-check",
                    maximum=15,
                )
                cleanup_ok = not remaining.stdout.strip()
            except Exception:
                cleanup_ok = False
            try:
                if SECRET_FILE.exists():
                    _remove_secret(SECRET_FILE)
                cleanup_ok = cleanup_ok and not SECRET_FILE.exists()
            except Exception:
                cleanup_ok = False

    if not cleanup_ok:
        raise PublicExampleError("public-example-cleanup-failed")
    if primary_failure is not None:
        if isinstance(primary_failure, PublicExampleError):
            raise primary_failure
        raise PublicExampleError("public-example-failed")


def main() -> int:
    try:
        run()
    except PublicExampleError as error:
        print(f"public-example: {error}", file=sys.stderr)
        return 1
    print("public-compose-ready=true")
    print("public-secret-metadata=1000:0:400 directory=700")
    print("public-compose-cleanup=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
