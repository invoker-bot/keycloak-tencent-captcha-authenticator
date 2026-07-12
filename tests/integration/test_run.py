import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run as acceptance_run


class AcceptanceRunnerCleanupTest(unittest.TestCase):
    def test_cleanup_failure_replaces_sensitive_primary_failure_with_bounded_label(self) -> None:
        secret_marker = "sensitive-primary-failure-details"

        with (
            mock.patch.object(acceptance_run, "JAR_FILE", acceptance_run.SECRET_EXAMPLE),
            mock.patch.object(acceptance_run, "_run", side_effect=ValueError(secret_marker)),
            mock.patch.object(acceptance_run, "_cleanup", return_value=False),
            mock.patch.object(acceptance_run, "_arm_work_alarm", return_value=None),
            mock.patch.object(acceptance_run, "_disarm_work_alarm"),
        ):
            with self.assertRaises(acceptance_run.RunnerError) as raised:
                acceptance_run.run()

        self.assertEqual("cleanup-failed", str(raised.exception))
        self.assertNotIn(secret_marker, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
