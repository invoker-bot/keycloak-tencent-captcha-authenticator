import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from release_artifacts import build_release_artifacts  # noqa: E402
from verify_release_artifacts import ArtifactVerificationError, verify_release_artifacts  # noqa: E402


VERSION = "0.1.0"
JAR_NAME = f"keycloak-tencent-captcha-authenticator-{VERSION}.jar"
SBOM_NAME = f"keycloak-tencent-captcha-authenticator-{VERSION}.cdx.json"


def cyclone_dx(version=VERSION):
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "component": {
                "type": "library",
                "group": "io.github.invoker-bot",
                "name": "keycloak-tencent-captcha-authenticator",
                "version": version,
            }
        },
    }


class ReleaseArtifactsTest(unittest.TestCase):
    def test_builds_and_verifies_exact_release_set(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.mkdir()
            (target / JAR_NAME).write_bytes(b"synthetic-provider-jar")
            (target / "bom.json").write_text(json.dumps(cyclone_dx()), encoding="utf-8")

            output = build_release_artifacts(VERSION, project_root=root)

            self.assertEqual({JAR_NAME, SBOM_NAME, "SHA256SUMS"}, {path.name for path in output.iterdir()})
            self.assertEqual(VERSION, verify_release_artifacts(output))
            checksum_lines = (output / "SHA256SUMS").read_text(encoding="ascii").splitlines()
            self.assertEqual([JAR_NAME, SBOM_NAME], [line.split("  ", 1)[1] for line in checksum_lines])

    def test_rejects_extra_missing_empty_and_tampered_assets(self):
        mutations = {
            "extra": lambda directory: (directory / "unexpected.txt").write_text("x", encoding="utf-8"),
            "missing": lambda directory: (directory / SBOM_NAME).unlink(),
            "empty": lambda directory: (directory / JAR_NAME).write_bytes(b""),
            "tampered": lambda directory: (directory / JAR_NAME).write_bytes(b"changed"),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                output = self._valid_release(Path(temporary))
                mutate(output)
                with self.assertRaises(ArtifactVerificationError):
                    verify_release_artifacts(output)

    def test_rejects_checksum_path_traversal_duplicate_and_wrong_sbom(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = self._valid_release(Path(temporary))
            digest = hashlib.sha256((output / JAR_NAME).read_bytes()).hexdigest()
            (output / "SHA256SUMS").write_text(f"{digest}  ../{JAR_NAME}\n{digest}  {JAR_NAME}\n", encoding="ascii")
            with self.assertRaises(ArtifactVerificationError):
                verify_release_artifacts(output)

        with tempfile.TemporaryDirectory() as temporary:
            output = self._valid_release(Path(temporary))
            (output / SBOM_NAME).write_text(json.dumps(cyclone_dx("9.9.9")), encoding="utf-8")
            self._rewrite_checksums(output)
            with self.assertRaises(ArtifactVerificationError):
                verify_release_artifacts(output)

    @staticmethod
    def _rewrite_checksums(output):
        lines = []
        for name in (JAR_NAME, SBOM_NAME):
            lines.append(f"{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n")
        (output / "SHA256SUMS").write_text("".join(lines), encoding="ascii")

    def _valid_release(self, root):
        target = root / "target"
        target.mkdir()
        (target / JAR_NAME).write_bytes(b"synthetic-provider-jar")
        (target / "bom.json").write_text(json.dumps(cyclone_dx()), encoding="utf-8")
        return build_release_artifacts(VERSION, project_root=root)


if __name__ == "__main__":
    unittest.main()
