#!/usr/bin/env python3
"""Test registration against a real Keycloak and a signed-request TLS API fixture.

No provider code or endpoint is replaced. Only this disposable network resolves
captcha.tencentcloudapi.com to the stub, with its own ephemeral TLS truststore.
Requires Docker, OpenSSL and keytool (Java 21); cleans all containers in finally.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

from configure_and_verify import AcceptanceVerifier
from run import _available_loopback_port
from verify_registration import verify
from verify_locales import verify as verify_locales

ROOT = Path(__file__).resolve().parents[2]


def run(args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, timeout=300, **kwargs).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-image', default='quay.io/keycloak/keycloak:26.7.0')
    parser.add_argument('--openssl', default='openssl')
    parser.add_argument('--theme-jar', type=Path, help='Additionally verify an invoker-shadcn theme JAR')
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:12]
    network = 'captcha-registration-' + suffix
    server, keycloak, image = network + '-api', network + '-kc', network + ':test'
    port = _available_loopback_port()
    keytool = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
    with tempfile.TemporaryDirectory(prefix='captcha-registration-') as directory:
        work = Path(directory)
        try:
            run([args.openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                 '-subj', '/CN=captcha.tencentcloudapi.com', '-addext', 'subjectAltName=DNS:captcha.tencentcloudapi.com',
                 '-keyout', str(work / 'server.key'), '-out', str(work / 'server.pem')])
            run([keytool, '-importcert', '-noprompt', '-alias', 'test-only', '-file', str(work / 'server.pem'),
                 '-keystore', str(work / 'trust.p12'), '-storetype', 'PKCS12', '-storepass', 'changeit'])
            shutil.copy2(ROOT / 'tests/integration/tencent_stub.py', work / 'server.py')
            (work / 'captcha.env').write_bytes((ROOT / 'tests/integration/secrets/tencent-captcha.env.example').read_text().encode())
            shutil.copy2(ROOT / 'target/keycloak-tencent-captcha-authenticator-0.1.2.jar', work / 'provider.jar')
            theme_copy = ''
            if args.theme_jar:
                shutil.copy2(args.theme_jar, work / 'theme.jar')
                theme_copy = 'COPY --chown=1000:0 theme.jar /opt/keycloak/providers/invoker-shadcn.jar\n'
            (work / 'Dockerfile').write_text('FROM ' + args.base_image + '\n' + theme_copy + '''
USER root
COPY --chown=1000:0 captcha.env /run/secrets/tencent-captcha.env
COPY --chown=1000:0 trust.p12 /tmp/test-trust.p12
COPY --chown=1000:0 provider.jar /opt/keycloak/providers/keycloak-tencent-captcha-authenticator.jar
RUN chown -R 1000:0 /run/secrets && chmod 700 /run/secrets && chmod 600 /run/secrets/tencent-captcha.env && chmod 644 /tmp/test-trust.p12
USER 1000
RUN /opt/keycloak/bin/kc.sh build
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
''')
            print('Building disposable registration test image', flush=True)
            run(['docker', 'build', '-t', image, str(work)])
            run(['docker', 'network', 'create', network])
            run(['docker', 'run', '-d', '--name', server, '--network', network,
                 '--network-alias', 'captcha.tencentcloudapi.com', '--mount', 'type=bind,source=' + str(work) + ',target=/fixture,readonly',
                 'python:3.11-slim', 'python', '/fixture/server.py'])
            run(['docker', 'run', '-d', '--name', keycloak, '--network', network, '-p', f'127.0.0.1:{port}:8080',
                 '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=integration-admin',
                 '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=integration-password-not-a-secret',
                 '-e', 'JAVA_OPTS_APPEND=-Djavax.net.ssl.trustStore=/tmp/test-trust.p12 -Djavax.net.ssl.trustStorePassword=changeit',
                 image, 'start', '--optimized', '--http-enabled=true', '--hostname-strict=false'])
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 120
            while True:
                try:
                    AcceptanceVerifier(base).refresh_admin_token()
                    break
                except Exception:
                    if time.monotonic() > deadline:
                        raise RuntimeError('registration Keycloak readiness timeout') from None
                    time.sleep(1)
            print('Testing registration gates', flush=True)
            results = verify(base)
            results.update(verify_locales(base))
            if args.theme_jar:
                results['customThemeLocales'] = verify_locales(base, 'invoker-shadcn')
            calls = [json.loads(line) for line in run(['docker', 'logs', server]).splitlines()]
            assert len(calls) == (11 if args.theme_jar else 7) and all(call['signed'] for call in calls)
            assert [call['accepted'] for call in calls] == [False, True, False] + [True] * (len(calls) - 3)
            results['realProviderSignedTlsRequests'] = True
            print(json.dumps(results, indent=2))
        except Exception:
            # Only disposable fixtures with synthetic credentials are ever logged.
            logs = subprocess.run(['docker', 'logs', '--tail', '45', keycloak], capture_output=True, timeout=30)
            print(logs.stdout.decode(errors='replace'), flush=True)
            raise
        finally:
            for name in (keycloak, server):
                subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=30)
            subprocess.run(['docker', 'network', 'rm', network], capture_output=True, timeout=30)
            subprocess.run(['docker', 'image', 'rm', image], capture_output=True, timeout=30)


if __name__ == '__main__':
    main()
