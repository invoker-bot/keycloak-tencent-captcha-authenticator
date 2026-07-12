#!/usr/bin/env python3
"""Create the exact, portable release asset set from Maven build outputs."""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import tempfile
from pathlib import Path

ARTIFACT_ID = "keycloak-tencent-captcha-authenticator"
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?")


class ArtifactBuildError(RuntimeError):
    """Release inputs are absent or do not match the requested version."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_release_artifacts(version: str, *, project_root: Path | None = None) -> Path:
    """Copy the Maven JAR/SBOM and generate deterministic checksums in ``dist``."""
    if VERSION_PATTERN.fullmatch(version) is None:
        raise ValueError("version must be a Maven-compatible semantic version")
    root = (project_root or Path(__file__).resolve().parents[1]).resolve()
    jar_name = f"{ARTIFACT_ID}-{version}.jar"
    sbom_name = f"{ARTIFACT_ID}-{version}.cdx.json"
    source_jar = root / "target" / jar_name
    source_sbom = root / "target" / "bom.json"
    for source in (source_jar, source_sbom):
        if not source.is_file() or source.stat().st_size == 0:
            raise ArtifactBuildError(f"required non-empty build output is missing: {source.name}")

    staging = Path(tempfile.mkdtemp(prefix=".release-artifacts-", dir=root))
    destination = root / "dist"
    try:
        shutil.copyfile(source_jar, staging / jar_name)
        shutil.copyfile(source_sbom, staging / sbom_name)
        checksum_text = "".join(
            f"{_sha256(staging / name)}  {name}\n" for name in (jar_name, sbom_name)
        )
        (staging / "SHA256SUMS").write_text(checksum_text, encoding="ascii", newline="\n")
        if destination.is_dir():
            shutil.rmtree(destination)
        elif destination.exists():
            destination.unlink()
        staging.replace(destination)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        output = build_release_artifacts(args.version)
    except (ArtifactBuildError, OSError, ValueError) as error:
        parser.exit(1, f"release-artifacts: {error}\n")
    for asset in sorted(output.iterdir()):
        print(asset.name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
