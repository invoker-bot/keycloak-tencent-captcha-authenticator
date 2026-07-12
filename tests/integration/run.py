#!/usr/bin/env python3
"""Run the fresh-Keycloak acceptance suite with a hard ten-minute bound."""

from __future__ import annotations

import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from configure_and_verify import AcceptanceError, AcceptanceVerifier

PROJECT_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = PROJECT_ROOT / "tests/integration/compose.yaml"
JAR_FILE = PROJECT_ROOT / "target/keycloak-tencent-captcha-authenticator-0.1.0.jar"
SECRET_EXAMPLE = PROJECT_ROOT / "tests/integration/secrets/tencent-captcha.env.example"
WORK_BUDGET_SECONDS = 540
CLEANUP_BUDGET_SECONDS = 60
COMMAND_BUDGET_SECONDS = 300
READY_BUDGET_SECONDS = 120

EXPECTED_RESULTS: dict[str, str] = {
    "ready": "true",
    "providerDiscovered": "true",
    "embeddedTemplate": "true",
    "browserFlow": "tencent-captcha-test-browser",
    "captchaRequirement": "REQUIRED",
    "missingSecretFailClosed": "true",
    "invalidSecretFailClosed": "true",
    "challengeCspExact": "true",
    "scriptUrlExact": "true",
    "constructorOptionsAidEncryptedOnly": "true",
    "loginHeadersUnchanged": "true",
    "registrationHeadersUnchanged": "true",
    "errorHeadersUnchanged": "true",
    "masterHeadersUnchanged": "true",
    "accountConsoleHeadersUnchanged": "true",
}


class RunnerError(RuntimeError):
    """A bounded acceptance-runner failure with no command output attached."""


class Deadline:
    def __init__(self, seconds: int):
        self._expires_at = time.monotonic() + seconds

    def timeout(self, maximum: int) -> float:
        remaining = self._expires_at - time.monotonic()
        if remaining <= 0:
            raise RunnerError("work-deadline-exceeded")
        return max(0.1, min(float(maximum), remaining))


def _work_deadline_expired(_signum: int, _frame: object) -> None:
    raise RunnerError("work-deadline-exceeded")


def _arm_work_alarm() -> object | None:
    if not hasattr(signal, "SIGALRM") or not hasattr(signal, "setitimer"):
        return None
    previous = signal.getsignal(signal.SIGALRM)
    signal.signal(signal.SIGALRM, _work_deadline_expired)
    signal.setitimer(signal.ITIMER_REAL, WORK_BUDGET_SECONDS)
    return previous


def _disarm_work_alarm(previous: object | None) -> None:
    if previous is None:
        return
    signal.setitimer(signal.ITIMER_REAL, 0)
    signal.signal(signal.SIGALRM, previous)


def _run(
    command: list[str],
    *,
    env: dict[str, str],
    deadline: Deadline,
    label: str,
    maximum: int = COMMAND_BUDGET_SECONDS,
) -> None:
    try:
        completed = subprocess.run(
            command,
            cwd=PROJECT_ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=deadline.timeout(maximum),
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise RunnerError(f"{label}-command-unavailable-or-timed-out") from None
    if completed.returncode != 0:
        raise RunnerError(f"{label}-exit-{completed.returncode}")


def _cleanup(command: list[str], env: dict[str, str], ownership_container: str) -> bool:
    expires_at = time.monotonic() + CLEANUP_BUDGET_SECONDS
    ownership_cleanup_ok = True
    try:
        removal = subprocess.run(
            ["docker", "rm", "--force", ownership_container],
            cwd=PROJECT_ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=min(10.0, max(0.1, expires_at - time.monotonic())),
            check=False,
        )
        if removal.returncode != 0:
            inspection = subprocess.run(
                ["docker", "container", "inspect", ownership_container],
                cwd=PROJECT_ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=min(10.0, max(0.1, expires_at - time.monotonic())),
                check=False,
            )
            ownership_cleanup_ok = inspection.returncode != 0
    except (OSError, subprocess.TimeoutExpired):
        ownership_cleanup_ok = False
    try:
        completed = subprocess.run(
            [*command, "down", "--volumes", "--remove-orphans"],
            cwd=PROJECT_ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=max(0.1, expires_at - time.monotonic()),
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return ownership_cleanup_ok and completed.returncode == 0


def _available_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind(("127.0.0.1", 0))
        return int(server.getsockname()[1])


def _wait_ready(base_url: str, deadline: Deadline) -> None:
    ready_deadline = time.monotonic() + deadline.timeout(READY_BUDGET_SECONDS)
    endpoint = f"{base_url}/realms/master/.well-known/openid-configuration"
    while time.monotonic() < ready_deadline:
        try:
            with urllib.request.urlopen(endpoint, timeout=min(3.0, deadline.timeout(3))) as response:
                if response.status == 200:
                    response.read(1)
                    return
        except (TimeoutError, urllib.error.URLError, OSError):
            pass
        time.sleep(min(1.0, deadline.timeout(1)))
    raise RunnerError("keycloak-readiness-timeout")


def _phase_up(
    compose: list[str],
    env: dict[str, str],
    deadline: Deadline,
    *,
    build: bool,
    label: str,
) -> None:
    command = [*compose, "up", "--detach", "--force-recreate"]
    if build:
        command.append("--build")
    _run(command, env=env, deadline=deadline, label=label)


def _phase_down(compose: list[str], env: dict[str, str], deadline: Deadline, label: str) -> None:
    _run([*compose, "down", "--remove-orphans"], env=env, deadline=deadline, label=label, maximum=90)


def _write_secret_fixtures(directory: Path) -> tuple[Path, Path]:
    directory.chmod(0o700)
    valid = directory / "valid.env"
    invalid = directory / "invalid.env"
    shutil.copyfile(SECRET_EXAMPLE, valid)
    invalid.write_text("UNSUPPORTED_KEY=synthetic-invalid-value\n", encoding="utf-8", newline="\n")
    valid.chmod(0o400)
    invalid.chmod(0o400)
    return valid, invalid


def _normalize_linux_secret_ownership(
    directory: Path, env: dict[str, str], deadline: Deadline, container_name: str
) -> None:
    if not sys.platform.startswith("linux"):
        return
    command = [
        "docker",
        "run",
        "--rm",
        "--name",
        container_name,
        "--user",
        "0:0",
        "--entrypoint",
        "/bin/sh",
        "--mount",
        f"type=bind,source={directory},target=/acceptance-secrets",
        "quay.io/keycloak/keycloak:26.7.0",
        "-c",
        "chown 1000:0 /acceptance-secrets/valid.env /acceptance-secrets/invalid.env"
        " && chmod 0400 /acceptance-secrets/valid.env /acceptance-secrets/invalid.env"
        " && test \"$(stat -c '%u:%g:%a' /acceptance-secrets/valid.env)\" = 1000:0:400"
        " && test \"$(stat -c '%u:%g:%a' /acceptance-secrets/invalid.env)\" = 1000:0:400",
    ]
    _run(command, env=env, deadline=deadline, label="linux-secret-ownership", maximum=60)


def _normalized_results(raw: dict[str, str | bool]) -> dict[str, str]:
    normalized: dict[str, str] = {}
    for key, value in raw.items():
        if isinstance(value, bool):
            normalized[key] = "true" if value else "false"
        elif isinstance(value, str):
            normalized[key] = value
        else:
            raise RunnerError(f"result-{key}-has-invalid-type")
    return normalized


def run() -> dict[str, str]:
    if not JAR_FILE.is_file():
        raise RunnerError("provider-jar-missing-run-maven-verify-first")
    if not SECRET_EXAMPLE.is_file():
        raise RunnerError("synthetic-secret-fixture-missing")

    deadline = Deadline(WORK_BUDGET_SECONDS)
    port = _available_loopback_port()
    base_url = f"http://127.0.0.1:{port}"
    project_name = f"tencent-captcha-acceptance-{os.getpid()}"
    ownership_container = f"{project_name}-secret-ownership"
    compose = ["docker", "compose", "--project-name", project_name, "-f", str(COMPOSE_FILE)]
    base_env = os.environ.copy()
    base_env["KEYCLOAK_HTTP_PORT"] = str(port)
    failure: Exception | None = None
    results: dict[str, str | bool] = {}

    with tempfile.TemporaryDirectory(prefix="tencent-captcha-acceptance-") as temporary:
        valid_secret, invalid_secret = _write_secret_fixtures(Path(temporary))
        base_env["TENCENT_CAPTCHA_SECRET_FILE_HOST"] = str(valid_secret)
        verifier = AcceptanceVerifier(base_url)
        previous_alarm = _arm_work_alarm()
        try:
            _run([*compose, "config", "--quiet"], env=base_env, deadline=deadline, label="compose-config")

            missing_env = base_env.copy()
            missing_env["TENCENT_CAPTCHA_SECRET_FILE_CONTAINER"] = "/run/secrets/missing.env"
            _phase_up(compose, missing_env, deadline, build=True, label="missing-phase-up")
            _wait_ready(base_url, deadline)
            _normalize_linux_secret_ownership(Path(temporary), missing_env, deadline, ownership_container)
            results["ready"] = True
            results.update(verifier.configure())
            results["missingSecretFailClosed"] = verifier.verify_missing_secret()

            _phase_down(compose, missing_env, deadline, "missing-phase-down")
            invalid_env = base_env.copy()
            invalid_env["TENCENT_CAPTCHA_SECRET_FILE_HOST"] = str(invalid_secret)
            invalid_env["TENCENT_CAPTCHA_SECRET_FILE_CONTAINER"] = "/run/secrets/tencent-captcha.env"
            _phase_up(compose, invalid_env, deadline, build=False, label="invalid-phase-up")
            _wait_ready(base_url, deadline)
            verifier.refresh_admin_token()
            results["invalidSecretFailClosed"] = verifier.verify_invalid_secret()

            _phase_down(compose, invalid_env, deadline, "invalid-phase-down")
            valid_env = base_env.copy()
            valid_env["TENCENT_CAPTCHA_SECRET_FILE_CONTAINER"] = "/run/secrets/tencent-captcha.env"
            _phase_up(compose, valid_env, deadline, build=False, label="valid-phase-up")
            _wait_ready(base_url, deadline)
            verifier.refresh_admin_token()
            results.update(verifier.verify_valid_challenge())
        except (AcceptanceError, RunnerError, ValueError) as error:
            failure = error
        finally:
            _disarm_work_alarm(previous_alarm)
            cleanup_ok = _cleanup(compose, base_env, ownership_container)
            if not cleanup_ok:
                failure = RunnerError("cleanup-failed")

    if failure is not None:
        raise RunnerError(str(failure)) from None
    normalized = _normalized_results(results)
    if normalized != EXPECTED_RESULTS:
        mismatches = [key for key, expected in EXPECTED_RESULTS.items() if normalized.get(key) != expected]
        unexpected = sorted(set(normalized) - set(EXPECTED_RESULTS))
        labels = ",".join([*mismatches, *unexpected])
        raise RunnerError(f"structural-results-mismatch-{labels}")
    return normalized


def main() -> int:
    try:
        results = run()
    except RunnerError as error:
        print(f"acceptance-failed={error}", file=sys.stderr)
        return 1
    for key in EXPECTED_RESULTS:
        print(f"{key}={results[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
