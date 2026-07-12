import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import configure_and_verify


class FlowRepresentationTest(unittest.TestCase):
    def test_finds_keycloak_267_renamed_forms_subflow(self) -> None:
        executions = [
            {
                "authenticationFlow": True,
                "displayName": "tencent-captcha-test-browser Organization",
                "flowId": "organization-id",
                "level": 0,
            },
            {
                "authenticationFlow": True,
                "displayName": "tencent-captcha-test-browser forms",
                "flowId": "forms-id",
                "level": 0,
            },
            {
                "authenticationFlow": True,
                "displayName": "tencent-captcha-test-browser Browser - Conditional 2FA",
                "flowId": "two-factor-id",
                "level": 1,
            },
        ]

        alias = configure_and_verify._forms_alias_from_executions(executions)

        self.assertEqual("tencent-captcha-test-browser forms", alias)


if __name__ == "__main__":
    unittest.main()
