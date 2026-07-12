#!/usr/bin/env python3
"""Verify the exact JAR, SHA256SUMS, and CycloneDX JSON release set."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

ARTIFACT_ID = "keycloak-tencent-captcha-authenticator"
JAR_PATTERN = re.compile(rf"{re.escape(ARTIFACT_ID)}-(.+)\.jar")
CHECKSUM_LINE = re.compile(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)")


class ArtifactVerificationError(RuntimeError):
    """The supplied release directory violates the public artifact contract."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _release_version(directory: Path) -> str:
    jars = [path.name for path in directory.iterdir() if path.is_file() and JAR_PATTERN.fullmatch(path.name)]
    if len(jars) != 1:
        raise ArtifactVerificationError("release must contain exactly one versioned provider JAR")
    return JAR_PATTERN.fullmatch(jars[0]).group(1)  # type: ignore[union-attr]


def _verify_sbom(path: Path, version: str) -> None:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ArtifactVerificationError("SBOM must be valid UTF-8 CycloneDX JSON") from None
    if not isinstance(document, dict) or document.get("bomFormat") != "CycloneDX":
        raise ArtifactVerificationError("SBOM format must be CycloneDX")
    if not isinstance(document.get("specVersion"), str):
        raise ArtifactVerificationError("SBOM must declare a CycloneDX specVersion")
    component = document.get("metadata", {}).get("component", {})
    expected = {
        "type": "library",
        "group": "io.github.invoker-bot",
        "name": ARTIFACT_ID,
        "version": version,
    }
    if not isinstance(component, dict) or any(component.get(key) != value for key, value in expected.items()):
        raise ArtifactVerificationError("SBOM metadata does not identify the released Maven component")


def _verify_checksums(directory: Path, checksummed_names: set[str]) -> None:
    checksum_file = directory / "SHA256SUMS"
    try:
        lines = checksum_file.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError):
        raise ArtifactVerificationError("SHA256SUMS must be readable ASCII") from None
    parsed: dict[str, str] = {}
    for line in lines:
        match = CHECKSUM_LINE.fullmatch(line)
        if match is None or match.group(2) in parsed:
            raise ArtifactVerificationError("SHA256SUMS contains an invalid or duplicate entry")
        parsed[match.group(2)] = match.group(1)
    if set(parsed) != checksummed_names:
        raise ArtifactVerificationError("SHA256SUMS must cover the JAR and SBOM exactly")
    for name, expected in parsed.items():
        if _sha256(directory / name) != expected:
            raise ArtifactVerificationError(f"SHA-256 mismatch for {name}")


def verify_release_artifacts(directory: Path | str) -> str:
    """Validate the release directory and return its single inferred version."""
    release = Path(directory).resolve()
    if not release.is_dir():
        raise ArtifactVerificationError("release artifact directory does not exist")
    version = _release_version(release)
    jar_name = f"{ARTIFACT_ID}-{version}.jar"
    sbom_name = f"{ARTIFACT_ID}-{version}.cdx.json"
    expected_names = {jar_name, sbom_name, "SHA256SUMS"}
    actual_names = {path.name for path in release.iterdir()}
    if actual_names != expected_names:
        raise ArtifactVerificationError("release directory does not contain the exact approved asset set")
    for name in expected_names:
        asset = release / name
        if not asset.is_file() or asset.stat().st_size == 0:
            raise ArtifactVerificationError(f"release asset must be a non-empty regular file: {name}")
    _verify_sbom(release / sbom_name, version)
    _verify_checksums(release, {jar_name, sbom_name})
    return version


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        version = verify_release_artifacts(args.directory)
    except (ArtifactVerificationError, OSError) as error:
        parser.exit(1, f"verify-release-artifacts: {error}\n")
    print(f"release-artifacts-verified={version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
