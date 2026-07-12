import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import run_public_example
except ModuleNotFoundError:
    run_public_example = None


class PublicExampleAcceptanceTest(unittest.TestCase):
    def test_refuses_existing_regular_secret_without_touching_or_running_commands(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "tencent-captcha.env"
            target.write_text("private-existing-value", encoding="utf-8")
            helper = mock.Mock()
            command = mock.Mock()
            remove = mock.Mock()
            with (
                mock.patch.object(run_public_example, "_prepare_secret", helper),
                mock.patch.object(run_public_example, "_run", command),
                mock.patch.object(run_public_example, "_remove_secret", remove),
                mock.patch.object(run_public_example, "_require_linux"),
                mock.patch.object(run_public_example, "JAR_FILE", Path(__file__)),
                mock.patch.object(run_public_example, "SECRET_FILE", target),
            ):
                with self.assertRaisesRegex(
                    run_public_example.PublicExampleError, "^public-example-secret-already-exists$"
                ) as raised:
                    run_public_example.run()
            self.assertEqual("private-existing-value", target.read_text(encoding="utf-8"))
            self.assertNotIn("private-existing-value", str(raised.exception))
            helper.assert_not_called()
            command.assert_not_called()
            remove.assert_not_called()

    def test_refuses_existing_secret_symlink_without_touching_target_or_running_commands(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        with tempfile.TemporaryDirectory() as temporary:
            victim = Path(temporary) / "victim.env"
            victim.write_text("private-symlink-target", encoding="utf-8")
            target = Path(temporary) / "tencent-captcha.env"
            target.symlink_to(victim)
            helper = mock.Mock()
            command = mock.Mock()
            with (
                mock.patch.object(run_public_example, "_prepare_secret", helper),
                mock.patch.object(run_public_example, "_run", command),
                mock.patch.object(run_public_example, "_require_linux"),
                mock.patch.object(run_public_example, "JAR_FILE", Path(__file__)),
                mock.patch.object(run_public_example, "SECRET_FILE", target),
            ):
                with self.assertRaisesRegex(
                    run_public_example.PublicExampleError, "^public-example-secret-already-exists$"
                ) as raised:
                    run_public_example.run()
            self.assertTrue(target.is_symlink())
            self.assertTrue(target.samefile(victim))
            self.assertEqual("private-symlink-target", victim.read_text(encoding="utf-8"))
            self.assertNotIn("private-symlink-target", str(raised.exception))
            helper.assert_not_called()
            command.assert_not_called()

    def test_invokes_the_shipped_shell_helper(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        captured = []
        source = Path("/tmp/synthetic-public-example.env")
        with mock.patch.object(run_public_example, "_run", side_effect=lambda command, **kwargs: captured.append(command)), mock.patch.object(
            run_public_example.os, "geteuid", return_value=0
        ):
            run_public_example._prepare_secret(source, run_public_example.Deadline(1), {})
        self.assertEqual("prepare-secret.sh", run_public_example.PREPARE_SECRET.name)
        self.assertEqual([str(run_public_example.PREPARE_SECRET), str(source)], captured[0])

    def test_verifies_exact_linux_secret_metadata(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "secrets"
            directory.mkdir(mode=0o700)
            target = directory / "tencent-captcha.env"
            target.write_text("synthetic\n", encoding="utf-8")
            target.chmod(0o400)
            metadata = target.stat()
            with mock.patch.object(run_public_example, "EXPECTED_SECRET_UID", metadata.st_uid), mock.patch.object(
                run_public_example, "EXPECTED_SECRET_GID", metadata.st_gid
            ):
                run_public_example._verify_secret_metadata(directory, target)
            directory.chmod(0o755)
            with self.assertRaisesRegex(run_public_example.PublicExampleError, "secret-directory-mode-invalid"):
                run_public_example._verify_secret_metadata(directory, target)

    def test_always_runs_volume_cleanup_and_removes_secret_after_failure(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        commands = []

        def fake_run(command, **kwargs):
            commands.append(command)
            if "up" in command:
                raise run_public_example.PublicExampleError("synthetic-up-failure")
            return __import__("subprocess").CompletedProcess(command, 0, stdout="", stderr="")

        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "tencent-captcha.env"

            def prepare(*_args):
                target.write_text("synthetic", encoding="utf-8")
                return target

            with (
                mock.patch.object(run_public_example, "_run", side_effect=fake_run),
                mock.patch.object(run_public_example, "_prepare_secret", side_effect=prepare),
                mock.patch.object(run_public_example, "_verify_secret_metadata"),
                mock.patch.object(run_public_example, "_remove_secret", side_effect=lambda path: path.unlink()),
                mock.patch.object(run_public_example, "_require_linux"),
                mock.patch.object(run_public_example, "JAR_FILE", Path(__file__)),
                mock.patch.object(run_public_example, "SECRET_FILE", target),
            ):
                with self.assertRaisesRegex(run_public_example.PublicExampleError, "synthetic-up-failure"):
                    run_public_example.run()

            self.assertTrue(any(command[-4:] == ["down", "--volumes", "--remove-orphans", "--timeout"] or
                                "down" in command and "--volumes" in command and "--remove-orphans" in command
                                for command in commands))
            self.assertFalse(target.exists())

    def test_removes_secret_even_if_helper_fails_after_creating_it(self):
        self.assertIsNotNone(run_public_example, "run_public_example.py must exist")
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "tencent-captcha.env"

            def fail_after_write(*_args):
                target.write_text("synthetic", encoding="utf-8")
                raise run_public_example.PublicExampleError("prepare-secret-exit-1")

            completed = __import__("subprocess").CompletedProcess([], 0, stdout="", stderr="")
            with (
                mock.patch.object(run_public_example, "_prepare_secret", side_effect=fail_after_write),
                mock.patch.object(run_public_example, "_run", return_value=completed),
                mock.patch.object(run_public_example, "_remove_secret", side_effect=lambda path: path.unlink()),
                mock.patch.object(run_public_example, "_require_linux"),
                mock.patch.object(run_public_example, "JAR_FILE", Path(__file__)),
                mock.patch.object(run_public_example, "SECRET_FILE", target),
            ):
                with self.assertRaisesRegex(run_public_example.PublicExampleError, "prepare-secret-exit-1"):
                    run_public_example.run()
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
