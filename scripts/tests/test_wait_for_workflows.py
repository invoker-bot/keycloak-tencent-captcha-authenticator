import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from wait_for_workflows import WorkflowWaitError, wait_for_workflows  # noqa: E402

try:
    from verify_release_ref import ReleaseRefError, verify_release_ref
except ModuleNotFoundError:
    ReleaseRefError = RuntimeError
    verify_release_ref = None


SHA = "a" * 40


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class SequencedRunner:
    def __init__(self, results):
        self.results = {name: list(values) for name, values in results.items()}
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        workflow = command[command.index("--workflow") + 1]
        values = self.results[workflow]
        value = values.pop(0) if len(values) > 1 else values[0]
        return subprocess.CompletedProcess(command, 0, stdout=json.dumps(value), stderr="sensitive-stderr")


def run(head_sha=SHA, status="completed", conclusion="success"):
    return [{"headSha": head_sha, "status": status, "conclusion": conclusion, "databaseId": 42}]


class WaitForWorkflowsTest(unittest.TestCase):
    def test_waits_for_each_exact_workflow_and_head_sha(self):
        runner = SequencedRunner({
            "ci.yml": [run(status="in_progress", conclusion=""), run()],
            "codeql.yml": [run()],
        })
        clock = FakeClock()

        completed = wait_for_workflows(
            "invoker-bot/repository", SHA, ["ci.yml", "codeql.yml"], timeout=5,
            poll_interval=1, runner=runner, monotonic=clock.monotonic, sleep=clock.sleep,
        )

        self.assertEqual({"ci.yml", "codeql.yml"}, set(completed))
        for command, kwargs in runner.calls:
            self.assertEqual(["gh", "run", "list"], command[:3])
            self.assertIn("--workflow", command)
            self.assertIn("--repo", command)
            self.assertTrue(kwargs["capture_output"])
            self.assertTrue(kwargs["text"])
            self.assertGreater(kwargs["timeout"], 0)

    def test_ignores_stale_sha_until_exact_run_appears(self):
        runner = SequencedRunner({"ci.yml": [run("b" * 40), run()]})
        clock = FakeClock()
        result = wait_for_workflows(
            "owner/repo", SHA, ["ci.yml"], timeout=3, poll_interval=1,
            runner=runner, monotonic=clock.monotonic, sleep=clock.sleep,
        )
        self.assertEqual("success", result["ci.yml"]["conclusion"])

    def test_reports_missing_stale_failed_and_timed_out_without_command_output(self):
        cases = {
            "missing": ([[]], "no workflow run found"),
            "stale": ([run("b" * 40)], "no run for requested head SHA"),
            "failed": ([run(conclusion="failure")], "concluded with failure"),
            "timed": ([run(status="in_progress", conclusion="")], "timed out"),
        }
        for label, (responses, expected) in cases.items():
            with self.subTest(label=label):
                runner = SequencedRunner({"ci.yml": responses})
                clock = FakeClock()
                with self.assertRaisesRegex(WorkflowWaitError, expected) as raised:
                    wait_for_workflows(
                        "owner/repo", SHA, ["ci.yml"], timeout=2, poll_interval=1,
                        runner=runner, monotonic=clock.monotonic, sleep=clock.sleep,
                    )
                self.assertNotIn("sensitive-stderr", str(raised.exception))

    def test_rejects_invalid_arguments_before_invoking_gh(self):
        runner = SequencedRunner({"ci.yml": [[]]})
        for kwargs in (
            {"sha": "short", "workflows": ["ci.yml"], "timeout": 1},
            {"sha": SHA, "workflows": [], "timeout": 1},
            {"sha": SHA, "workflows": ["ci.yml", "ci.yml"], "timeout": 1},
            {"sha": SHA, "workflows": ["ci.yml"], "timeout": 0},
        ):
            with self.assertRaises(ValueError):
                wait_for_workflows(
                    "owner/repo", kwargs["sha"], kwargs["workflows"], timeout=kwargs["timeout"], runner=runner,
                )
        self.assertEqual([], runner.calls)


class ReleaseRefTest(unittest.TestCase):
    def test_requires_tag_commit_to_equal_current_origin_main_then_waits_exact_gates(self):
        self.assertIsNotNone(verify_release_ref, "verify_release_ref.py must exist")
        calls = []

        def runner(command, **kwargs):
            calls.append(command)
            if command[:2] == ["git", "fetch"]:
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
            return subprocess.CompletedProcess(command, 0, stdout=SHA + "\n", stderr="")

        waited = []
        verify_release_ref(
            "owner/repo", timeout=12, poll_interval=2, runner=runner,
            waiter=lambda repo, sha, workflows, **kwargs: waited.append((repo, sha, workflows, kwargs)),
        )

        self.assertEqual(["git", "fetch", "--no-tags", "origin", "+refs/heads/main:refs/remotes/origin/main"], calls[0])
        self.assertIn(["git", "rev-parse", "HEAD^{commit}"], calls)
        self.assertIn(["git", "rev-parse", "refs/remotes/origin/main"], calls)
        self.assertEqual(("owner/repo", SHA, ["ci.yml", "codeql.yml", "secret-scan.yml"]), waited[0][:3])
        self.assertEqual(12, waited[0][3]["timeout"])

    def test_rejects_tag_not_at_current_main_without_waiting(self):
        self.assertIsNotNone(verify_release_ref, "verify_release_ref.py must exist")
        outputs = iter(["", SHA + "\n", "b" * 40 + "\n"])

        def runner(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, stdout=next(outputs), stderr="sensitive")

        waiter = mock.Mock()
        with self.assertRaisesRegex(ReleaseRefError, "tag-commit-is-not-current-main") as raised:
            verify_release_ref("owner/repo", timeout=10, runner=runner, waiter=waiter)
        waiter.assert_not_called()
        self.assertNotIn("sensitive", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
