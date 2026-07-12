#!/usr/bin/env python3
"""Admin REST configuration and HTTP-only acceptance checks for a disposable Keycloak."""

from __future__ import annotations

import html
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

ADMIN_USERNAME = "integration-admin"
ADMIN_PASSWORD = "integration-password-not-a-secret"
PROTECTED_REALM = "tencent-captcha-test"
ISOLATION_REALM = "tencent-captcha-isolation"
CLIENT_ID = "acceptance-client"
FLOW_ALIAS = "tencent-captcha-test-browser"
PROVIDER_ID = "tencent-captcha"
TENCENT_SCRIPT_URL = "https://turing.captcha.qcloud.com/TJCaptcha.js"
BASE_CSP = "frame-src 'self'; frame-ancestors 'self'; object-src 'none'"
MAX_BODY_BYTES = 2 * 1024 * 1024
SECURITY_HEADERS = (
    "X-Frame-Options",
    "Content-Security-Policy",
    "Content-Security-Policy-Report-Only",
    "X-Content-Type-Options",
    "X-Robots-Tag",
    "Strict-Transport-Security",
    "Referrer-Policy",
)


class AcceptanceError(RuntimeError):
    """A bounded, non-sensitive acceptance failure."""


@dataclass(frozen=True)
class HttpResponse:
    status: int
    headers: Any
    body: bytes
    url: str


def _read_bounded(stream: Any) -> bytes:
    body = stream.read(MAX_BODY_BYTES + 1)
    if len(body) > MAX_BODY_BYTES:
        raise AcceptanceError("http-response-too-large")
    return body


def _request(
    url: str,
    *,
    method: str = "GET",
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 10.0,
) -> HttpResponse:
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return HttpResponse(response.status, response.headers, _read_bounded(response), response.geturl())
    except urllib.error.HTTPError as error:
        try:
            body = _read_bounded(error)
        finally:
            error.close()
        return HttpResponse(error.code, error.headers, body, error.geturl())
    except (TimeoutError, urllib.error.URLError, OSError):
        raise AcceptanceError("http-transport-failed") from None


def _json(response: HttpResponse) -> Any:
    try:
        return json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise AcceptanceError("admin-json-invalid") from None


def _query(**values: str) -> str:
    return urllib.parse.urlencode(values)


def _segment(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def _forms_alias_from_executions(executions: list[dict[str, Any]]) -> str:
    candidates = [
        execution.get("displayName")
        for execution in executions
        if execution.get("authenticationFlow") is True
        and execution.get("level") == 0
        and isinstance(execution.get("displayName"), str)
        and (
            execution["displayName"].casefold() == "forms"
            or execution["displayName"].casefold().endswith(" forms")
        )
    ]
    if len(candidates) != 1 or not isinstance(candidates[0], str):
        raise AcceptanceError("browser-forms-subflow-missing")
    return candidates[0]


class AcceptanceVerifier:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self._token: str | None = None
        self._baseline: dict[str, tuple[Any, ...]] = {}
        self._baseline_statuses: dict[str, int] = {}

    def refresh_admin_token(self) -> None:
        form = urllib.parse.urlencode(
            {
                "grant_type": "password",
                "client_id": "admin-cli",
                "username": ADMIN_USERNAME,
                "password": ADMIN_PASSWORD,
            }
        ).encode("ascii")
        response = _request(
            f"{self.base_url}/realms/master/protocol/openid-connect/token",
            method="POST",
            data=form,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        if response.status != 200:
            raise AcceptanceError(f"admin-token-status-{response.status}")
        payload = _json(response)
        token = payload.get("access_token") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise AcceptanceError("admin-token-missing")
        self._token = token

    def configure(self) -> dict[str, str | bool]:
        self.refresh_admin_token()
        self._replace_realm(PROTECTED_REALM, registration_allowed=False)
        self._replace_realm(ISOLATION_REALM, registration_allowed=True)
        self._create_client(PROTECTED_REALM)
        self._create_client(ISOLATION_REALM)

        providers = self._admin_json("GET", f"/admin/realms/{PROTECTED_REALM}/authentication/authenticator-providers")
        provider_discovered = isinstance(providers, list) and any(
            isinstance(provider, dict)
            and (provider.get("id") == PROVIDER_ID or provider.get("providerId") == PROVIDER_ID)
            for provider in providers
        )
        if not provider_discovered:
            raise AcceptanceError("provider-not-discovered")

        self._copy_and_configure_browser_flow()
        self._capture_isolation_baseline()
        realm = self._admin_json("GET", f"/admin/realms/{PROTECTED_REALM}")
        if not isinstance(realm, dict):
            raise AcceptanceError("realm-representation-invalid")
        realm["browserFlow"] = FLOW_ALIAS
        self._admin_json("PUT", f"/admin/realms/{PROTECTED_REALM}", realm, expected=(204,))

        execution = self._captcha_execution()
        return {
            "providerDiscovered": provider_discovered,
            "browserFlow": FLOW_ALIAS,
            "captchaRequirement": execution.get("requirement", ""),
        }

    def verify_missing_secret(self) -> bool:
        response = _request(self._login_url(PROTECTED_REALM))
        return response.status == 503 and b"data-captcha-challenge" not in response.body

    def verify_invalid_secret(self) -> bool:
        response = _request(self._login_url(PROTECTED_REALM))
        return response.status == 503 and b"data-captcha-challenge" not in response.body

    def verify_valid_challenge(self) -> dict[str, bool]:
        response = _request(self._login_url(PROTECTED_REALM))
        if response.status != 200:
            raise AcceptanceError(f"captcha-challenge-status-{response.status}")
        try:
            page = response.body.decode("utf-8")
        except UnicodeDecodeError:
            raise AcceptanceError("captcha-challenge-not-utf8") from None

        nonce_match = re.search(r'id="tencent-captcha-client"[\s\S]*?nonce="([A-Za-z0-9_-]{22})"', page)
        if nonce_match is None:
            raise AcceptanceError("captcha-challenge-nonce-missing")
        nonce = nonce_match.group(1)
        expected_csp = (
            "frame-src 'self' https://turing.captcha.qcloud.com; frame-ancestors 'self'; object-src 'none'; "
            f"script-src 'nonce-{nonce}' https://turing.captcha.qcloud.com; "
            "connect-src https://turing.captcha.qcloud.com"
        )
        csp_values = response.headers.get_all("Content-Security-Policy") or []
        csp_exact = csp_values == [expected_csp]

        script_attribute = re.search(r'data-script-url="([^"]+)"', page)
        script_url_exact = (
            script_attribute is not None
            and html.unescape(script_attribute.group(1)) == TENCENT_SCRIPT_URL
            and page.count(TENCENT_SCRIPT_URL) == 1
            and "captcha.qcloud.com" not in page.replace(TENCENT_SCRIPT_URL, "")
        )

        module_match = re.search(
            r'id="tencent-captcha-client"[\s\S]*?src="([^"]*?/js/tencent-captcha-client\.js)"', page
        )
        if module_match is None:
            raise AcceptanceError("captcha-browser-module-missing")
        module_url = urllib.parse.urljoin(response.url, html.unescape(module_match.group(1)))
        module = _request(module_url)
        if module.status != 200:
            raise AcceptanceError(f"captcha-browser-module-status-{module.status}")
        try:
            module_source = module.body.decode("utf-8")
        except UnicodeDecodeError:
            raise AcceptanceError("captcha-browser-module-not-utf8") from None
        constructor_exact = (
            len(re.findall(r"new\s+TencentCaptcha\s*\(", module_source)) == 1
            and re.search(
                r"new\s+TencentCaptcha\s*\(\s*appId\s*,\s*finish\s*,\s*\{\s*aidEncrypted\s*}\s*\)",
                module_source,
            )
            is not None
            and "aidEncryptedType" not in module_source
        )

        isolation = self._verify_header_isolation()
        return {
            "embeddedTemplate": "data-captcha-challenge" in page,
            "challengeCspExact": csp_exact,
            "scriptUrlExact": script_url_exact,
            "constructorOptionsAidEncryptedOnly": constructor_exact,
            **isolation,
        }

    def _replace_realm(self, realm: str, *, registration_allowed: bool) -> None:
        current = self._admin("GET", f"/admin/realms/{realm}")
        if current.status == 200:
            self._admin_json("DELETE", f"/admin/realms/{realm}", expected=(204,))
        elif current.status != 404:
            raise AcceptanceError(f"realm-probe-status-{current.status}")
        representation = {
            "realm": realm,
            "enabled": True,
            "registrationAllowed": registration_allowed,
            "browserSecurityHeaders": {
                "xFrameOptions": "SAMEORIGIN",
                "contentSecurityPolicy": BASE_CSP,
                "contentSecurityPolicyReportOnly": "",
                "xContentTypeOptions": "nosniff",
                "xRobotsTag": "none",
                "strictTransportSecurity": "max-age=31536000; includeSubDomains",
                "referrerPolicy": "no-referrer",
            },
        }
        self._admin_json("POST", "/admin/realms", representation, expected=(201,))

    def _create_client(self, realm: str) -> None:
        callback = f"{self.base_url}/callback"
        representation = {
            "clientId": CLIENT_ID,
            "enabled": True,
            "publicClient": True,
            "standardFlowEnabled": True,
            "directAccessGrantsEnabled": False,
            "redirectUris": [callback],
            "webOrigins": [self.base_url],
        }
        self._admin_json("POST", f"/admin/realms/{realm}/clients", representation, expected=(201,))

    def _copy_and_configure_browser_flow(self) -> None:
        self._admin_json(
            "POST",
            f"/admin/realms/{PROTECTED_REALM}/authentication/flows/browser/copy",
            {"newName": FLOW_ALIAS},
            expected=(201,),
        )
        executions = self._flow_executions(FLOW_ALIAS)
        forms_alias = _forms_alias_from_executions(executions)
        self._forms_alias = forms_alias

        self._admin_json(
            "POST",
            f"/admin/realms/{PROTECTED_REALM}/authentication/flows/{_segment(forms_alias)}/executions/execution",
            {"provider": PROVIDER_ID},
            expected=(201,),
        )
        execution = self._captcha_execution()
        execution["requirement"] = "REQUIRED"
        self._admin_json(
            "PUT",
            f"/admin/realms/{PROTECTED_REALM}/authentication/flows/{_segment(forms_alias)}/executions",
            execution,
            expected=(204,),
        )

        for _ in range(16):
            executions = self._flow_executions(forms_alias)
            captcha = next((item for item in executions if item.get("providerId") == PROVIDER_ID), None)
            username = next(
                (item for item in executions if item.get("displayName") == "Username Password Form"), None
            )
            if captcha is None or username is None:
                raise AcceptanceError("forms-execution-missing")
            if int(captcha.get("index", 10_000)) < int(username.get("index", -1)):
                return
            execution_id = captcha.get("id")
            if not isinstance(execution_id, str):
                raise AcceptanceError("captcha-execution-id-missing")
            self._admin_json(
                "POST",
                f"/admin/realms/{PROTECTED_REALM}/authentication/executions/{_segment(execution_id)}/raise-priority",
                expected=(204,),
            )
        raise AcceptanceError("captcha-execution-order-not-reached")

    def _captcha_execution(self) -> dict[str, Any]:
        forms_alias = getattr(self, "_forms_alias", None)
        if not isinstance(forms_alias, str):
            forms_alias = _forms_alias_from_executions(self._flow_executions(FLOW_ALIAS))
            self._forms_alias = forms_alias
        execution = next(
            (item for item in self._flow_executions(forms_alias) if item.get("providerId") == PROVIDER_ID), None
        )
        if not isinstance(execution, dict):
            raise AcceptanceError("captcha-execution-missing")
        return execution

    def _flow_executions(self, alias: str) -> list[dict[str, Any]]:
        executions = self._admin_json(
            "GET", f"/admin/realms/{PROTECTED_REALM}/authentication/flows/{_segment(alias)}/executions"
        )
        if not isinstance(executions, list) or not all(isinstance(item, dict) for item in executions):
            raise AcceptanceError("flow-executions-invalid")
        return executions

    def _capture_isolation_baseline(self) -> None:
        snapshots = self._isolation_snapshots()
        self._baseline = {name: self._header_signature(response) for name, response in snapshots.items()}
        self._baseline_statuses = {name: response.status for name, response in snapshots.items()}

    def _verify_header_isolation(self) -> dict[str, bool]:
        if not self._baseline:
            raise AcceptanceError("header-baseline-missing")
        snapshots = self._isolation_snapshots()
        expected_status = {"login": 200, "registration": 200, "error": 400, "master": 200, "accountConsole": 200}
        return {
            f"{name}HeadersUnchanged": (
                self._baseline_statuses.get(name) == expected_status[name]
                and response.status == expected_status[name]
                and self._baseline[name] == self._header_signature(response)
                and self._has_browser_headers(response)
            )
            for name, response in snapshots.items()
        }

    def _isolation_snapshots(self) -> dict[str, HttpResponse]:
        auth_query = _query(
            client_id=CLIENT_ID,
            redirect_uri=f"{self.base_url}/callback",
            response_type="code",
            scope="openid",
        )
        master_query = _query(
            client_id="security-admin-console",
            redirect_uri=f"{self.base_url}/admin/master/console/",
            response_type="code",
            scope="openid",
        )
        return {
            "login": _request(self._login_url(ISOLATION_REALM)),
            "registration": _request(
                f"{self.base_url}/realms/{ISOLATION_REALM}/protocol/openid-connect/registrations?{auth_query}"
            ),
            "error": _request(
                f"{self.base_url}/realms/{ISOLATION_REALM}/protocol/openid-connect/auth?"
                + _query(client_id="missing-client", redirect_uri=f"{self.base_url}/callback", response_type="code")
            ),
            "master": _request(
                f"{self.base_url}/realms/master/protocol/openid-connect/auth?{master_query}"
            ),
            "accountConsole": _request(f"{self.base_url}/realms/{ISOLATION_REALM}/account/"),
        }

    @staticmethod
    def _header_signature(response: HttpResponse) -> tuple[Any, ...]:
        return tuple((name, tuple(response.headers.get_all(name) or ())) for name in SECURITY_HEADERS)

    @staticmethod
    def _has_browser_headers(response: HttpResponse) -> bool:
        return all(response.headers.get(name) for name in ("Content-Security-Policy", "X-Frame-Options", "X-Content-Type-Options"))

    def _login_url(self, realm: str) -> str:
        return f"{self.base_url}/realms/{realm}/protocol/openid-connect/auth?" + _query(
            client_id=CLIENT_ID,
            redirect_uri=f"{self.base_url}/callback",
            response_type="code",
            scope="openid",
        )

    def _admin(
        self, method: str, path: str, payload: Any | None = None
    ) -> HttpResponse:
        if self._token is None:
            raise AcceptanceError("admin-token-not-initialized")
        headers = {"Authorization": f"Bearer {self._token}"}
        data = None
        if payload is not None:
            data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        return _request(f"{self.base_url}{path}", method=method, data=data, headers=headers)

    def _admin_json(
        self,
        method: str,
        path: str,
        payload: Any | None = None,
        *,
        expected: tuple[int, ...] = (200,),
    ) -> Any:
        response = self._admin(method, path, payload)
        if response.status not in expected:
            raise AcceptanceError(f"admin-request-status-{response.status}")
        if response.status == 204 or not response.body:
            return None
        return _json(response)
