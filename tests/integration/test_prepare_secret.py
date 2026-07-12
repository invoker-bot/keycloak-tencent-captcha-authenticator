import importlib.util
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[2]
HELPER = PROJECT_ROOT / "examples/docker-compose/prepare_secret.py"
VALID_CONTENT = (
    "CAPTCHA_APP_ID=123456789\n"
    "CAPTCHA_APP_SECRET_KEY=value=with=equals\n"
    "TENCENT_SECRET_ID=synthetic-id\n"
    "TENCENT_SECRET_KEY=synthetic-key\n"
)


def load_helper():
    if not HELPER.is_file():
        return None
    spec = importlib.util.spec_from_file_location("prepare_secret", HELPER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PrepareSecretTest(unittest.TestCase):
    def setUp(self) -> None:
        self.helper = load_helper()
        self.assertIsNotNone(self.helper, "prepare_secret.py must exist")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "example"
        self.root.mkdir()
        self.source = self.root / "source.env"
        self.source.write_text(VALID_CONTENT, encoding="utf-8")

    def prepare(self) -> None:
        with (
            mock.patch.object(self.helper, "EXPECTED_UID", os.getuid()),
            mock.patch.object(self.helper, "EXPECTED_GID", os.getgid()),
        ):
            self.helper.prepare_secret(self.source, script_dir=self.root, require_linux=False)

    def test_prepares_restricted_file_atomically(self) -> None:
        self.prepare()

        secret_dir = self.root / "secrets"
        secret_file = secret_dir / "tencent-captcha.env"
        self.assertEqual(0o700, stat.S_IMODE(secret_dir.stat().st_mode))
        self.assertEqual(0o400, stat.S_IMODE(secret_file.stat().st_mode))
        self.assertEqual(VALID_CONTENT, secret_file.read_text(encoding="utf-8"))

    def test_rejects_source_symlink(self) -> None:
        link = self.root / "source-link.env"
        link.symlink_to(self.source)

        with self.assertRaises(self.helper.SecretPreparationError):
            with mock.patch.object(self.helper, "EXPECTED_UID", os.getuid()), mock.patch.object(
                self.helper, "EXPECTED_GID", os.getgid()
            ):
                self.helper.prepare_secret(link, script_dir=self.root, require_linux=False)

    def test_rejects_secret_directory_symlink(self) -> None:
        outside = Path(self.temporary.name) / "outside"
        outside.mkdir()
        (self.root / "secrets").symlink_to(outside, target_is_directory=True)

        with self.assertRaises(self.helper.SecretPreparationError):
            self.prepare()
        self.assertFalse((outside / "tencent-captcha.env").exists())

    def test_rejects_existing_target_symlink_without_touching_victim(self) -> None:
        secret_dir = self.root / "secrets"
        secret_dir.mkdir()
        victim = Path(self.temporary.name) / "victim"
        victim.write_text("unchanged", encoding="utf-8")
        (secret_dir / "tencent-captcha.env").symlink_to(victim)

        with self.assertRaises(self.helper.SecretPreparationError):
            self.prepare()
        self.assertEqual("unchanged", victim.read_text(encoding="utf-8"))

    def test_rejects_directory_identity_change_during_replace(self) -> None:
        secret_dir = self.root / "secrets"
        secret_dir.mkdir()
        original_replace = os.replace

        def replace_after_directory_swap(src, dst, **kwargs):
            secret_dir.rename(self.root / "opened-secrets")
            secret_dir.mkdir()
            return original_replace(src, dst, **kwargs)

        with mock.patch.object(self.helper.os, "replace", side_effect=replace_after_directory_swap):
            with self.assertRaises(self.helper.SecretPreparationError):
                self.prepare()

        self.assertFalse((secret_dir / "tencent-captcha.env").exists())


if __name__ == "__main__":
    unittest.main()
