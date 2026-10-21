import gzip
import hashlib
import sys
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch

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


class CompressedModuleTest(unittest.TestCase):
    def verify(self, compressed_source: bytes):
        source = b'new TencentCaptcha(appId, finish, {aidEncrypted, userLanguage});'
        version = hashlib.sha256(source).hexdigest()
        url = f'https://keycloak.test/resources/login/js/tencent-captcha-client-{version}.js'
        page = (
            '<script id="tencent-captcha-client" nonce="abcdefghijklmnopqrstuv" '
            f'src="{url}" data-script-url="{configure_and_verify.TENCENT_SCRIPT_URL}"></script>'
        ).encode()
        encodings = []

        def request(request_url, **kwargs):
            headers = Message()
            if request_url == url:
                encoding = kwargs.get('headers', {}).get('Accept-Encoding', 'identity')
                encodings.append(encoding)
                body = source
                if encoding == 'gzip':
                    headers['Content-Encoding'] = 'gzip'
                    body = gzip.compress(compressed_source)
            else:
                body = page
            return configure_and_verify.HttpResponse(200, headers, body, request_url)

        verifier = configure_and_verify.AcceptanceVerifier('https://keycloak.test')
        with patch.object(configure_and_verify, '_request', side_effect=request), \
                patch.object(verifier, '_verify_header_isolation', return_value={}):
            result = verifier.verify_valid_challenge()
        self.assertIn('gzip', encodings)
        return result

    def test_rejects_stale_gzip_when_identity_module_is_current(self):
        with self.assertRaisesRegex(configure_and_verify.AcceptanceError, 'module-version-mismatch'):
            self.verify(b'old click-only CAPTCHA client')

    def test_accepts_matching_gzip_and_identity_modules(self):
        result = self.verify(b'new TencentCaptcha(appId, finish, {aidEncrypted, userLanguage});')
        self.assertTrue(result['constructorOptionsIncludeSelectedLanguage'])

    def test_bounds_decompressed_resource_size(self):
        with self.assertRaisesRegex(configure_and_verify.AcceptanceError, 'http-response-too-large'):
            self.verify(b'X' * (configure_and_verify.MAX_BODY_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
