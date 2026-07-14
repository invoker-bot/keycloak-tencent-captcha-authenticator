# Keycloak Tencent CAPTCHA Authenticator

[简体中文](README.zh-CN.md)

An unofficial Keycloak authenticator that adds Tencent Cloud CAPTCHA as a standalone `REQUIRED` Browser Flow execution. It is delivered as one thin provider JAR and does not depend on a specific application, secret store, deployment platform, or third-party login theme.

This is not an official Tencent, Tencent Cloud, or Keycloak project. Tencent and Tencent Cloud names and marks belong to Tencent; Keycloak names and marks belong to their respective owners.

## Compatibility and release identity

| Extension | Keycloak | Java | Status |
| --- | --- | --- | --- |
| `0.1.x` | `26.7.0` | `21` | Supported and acceptance-tested baseline |
| Other versions | Other versions | Other versions | Not claimed until the container acceptance suite passes |

Maven coordinate: `io.github.invoker-bot:keycloak-tencent-captcha-authenticator:0.1.0`<br>
Java package: `io.github.invokerbot.keycloak.tencentcaptcha`<br>
Provider ID: `tencent-captcha`

The Maven group uses a hyphen in `invoker-bot`; the Java package does not. See [compatibility and the `0.1.x` contract](docs/compatibility.md).

The supported baseline is Keycloak 26.7.0 on Java 21.

## Before installing

1. In the Tencent Cloud CAPTCHA console, create or select a Web/App CAPTCHA application and obtain its `CaptchaAppId` and `AppSecretKey`.
2. Create a Tencent Cloud API identity for server-side verification. Grant only the CAM action `captcha:DescribeCaptchaResult` where your account's CAPTCHA authorization model permits it; do not grant broad administrator credentials. See Tencent Cloud's [CAM policy syntax](https://cloud.tencent.com/document/product/598/10603) and CAPTCHA product authorization documentation for your account.
3. Ensure browsers can resolve and reach `turing.captcha.qcloud.com` and the script-only origin `turing.captcha.gtimg.com`, and that the Keycloak server can resolve and reach `captcha.tencentcloudapi.com`. Tencent documents that these names use dynamic IP addresses, so allow by hostname rather than pinning IP addresses.
4. Configure Keycloak proxy headers and trusted proxy addresses correctly. The provider sends the address exposed by Keycloak's connection context as `UserIp`; without a trusted-proxy configuration, Tencent may receive the reverse proxy IP instead of the browser client IP.

Tencent references: [TJCaptcha/Web integration](https://cloud.tencent.com/document/product/1110/36828), [`DescribeCaptchaResult`](https://cloud.tencent.com/document/api/1110/36926), and [`aidEncrypted` authentication](https://cloud.tencent.com/document/product/1110/128489).

## Build

Use Java 21 or newer to build the Java 21 artifact:

```bash
./mvnw -B verify
```

The release JAR is `target/keycloak-tencent-captcha-authenticator-0.1.0.jar`.

## JAR installation

Stop Keycloak, copy the release JAR into its provider directory, rebuild the optimized image, and start it again:

```bash
install -m 0644 target/keycloak-tencent-captcha-authenticator-0.1.0.jar \
  /opt/keycloak/providers/keycloak-tencent-captcha-authenticator-0.1.0.jar
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

`kc.sh build` is required whenever this provider is added, replaced, rolled back, or removed from an optimized Keycloak installation. This follows the official [Keycloak Server Developer Guide](https://www.keycloak.org/docs/latest/server_development/index.html) and [provider configuration guide](https://www.keycloak.org/server/configuration-provider).

## Secret file

The default path is `/run/secrets/tencent-captcha.env`. The exact schema is four non-empty LF-separated UTF-8 assignments, with no unknown or duplicate keys:

```dotenv
CAPTCHA_APP_ID=<CAPTCHA_APP_ID>
CAPTCHA_APP_SECRET_KEY=<CAPTCHA_APP_SECRET_KEY>
TENCENT_SECRET_ID=<TENCENT_SECRET_ID>
TENCENT_SECRET_KEY=<TENCENT_SECRET_KEY>
```

`CAPTCHA_APP_ID` is a public browser identifier. The other three values are server-side secrets. Keep all four in one restricted file so replacement is atomic. The file must:

- be a regular, non-symlink file, at most 65,536 bytes;
- use mode `0400` or `0600` and be readable by the Keycloak runtime user;
- have an immediate parent that is a real directory, not a symlink, and is not group- or other-writable (`0700` is recommended);
- contain valid UTF-8 with LF line endings and exactly the four keys above.

The JVM system property `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE` has precedence over the same-named environment variable; blank values fall through, then the default path is used. This setting contains a path only. Secret values are not supported in environment variables, command-line arguments, Realm attributes, Authenticator Config, Realm exports, the provider JAR, or image layers.

See [configuration and rotation](docs/configuration.md).

## Docker Compose installation

The Linux example uses a read-only host bind mount because the provider validates POSIX ownership and mode. Docker Desktop and non-POSIX hosts do not guarantee equivalent host-file metadata.

```bash
./mvnw -B verify
sudo examples/docker-compose/prepare-secret.sh /secure/input/tencent-captcha.env
stat -c '%u:%g:%a' examples/docker-compose/secrets examples/docker-compose/secrets/tencent-captcha.env
docker compose -f examples/docker-compose/compose.yaml up --build --detach
```

Maintainers can exercise this exact public example end to end on Linux with Docker by running `python3 tests/integration/run_public_example.py` after `./mvnw -B verify`. The bounded runner refuses to start if `examples/docker-compose/secrets/tencent-captcha.env` already exists in any form, uses the public secret helper with synthetic values, verifies `700`/`1000:0:400` metadata, waits for readiness, and removes only the secret it generated along with containers, volumes, and orphans.

The expected directory mode is `700`; the expected file owner/mode is `1000:0:400`. UID `1000` is the runtime user declared by the pinned `quay.io/keycloak/keycloak:26.7.0` image. Substitute the verified runtime UID for another image. The helper is Linux-only, requires root to assign ownership, validates the source without following symlinks, and installs the target atomically. The example binds only to `127.0.0.1`; configure a production hostname, TLS, proxy headers, and administrative credentials separately.

The example intentionally does not use Compose interpolation, `env_file`, or top-level Compose secrets for credential values. See Docker's official [secrets guidance](https://docs.docker.com/compose/how-tos/use-secrets/) and [Compose trust model](https://docs.docker.com/compose/trust-model/).

## Configure the Browser Flow

In the Keycloak Admin Console for each protected realm:

1. Open **Authentication > Flows**.
2. Copy the built-in **Browser** flow; do not edit the built-in flow in place.
3. In the copied flow, open the **Forms** subflow.
4. Add the **Tencent CAPTCHA** execution (`tencent-captcha`).
5. Set it to `REQUIRED` and place it immediately before **Username Password Form**.
6. Bind the copied flow as the realm's Browser Flow and test it with a non-administrator account before rollout.

The built-in **SSO Cookie** execution precedes Forms. A valid SSO cookie therefore bypasses a fresh CAPTCHA. This is intentional. Registration, password reset, action-token flows, broker flows, and direct grant are not automatically protected; add separately designed controls if those entry points require CAPTCHA. Direct grant has no browser page and cannot use this execution.

In short: registration, reset, and direct grant remain outside this Browser Flow unless separately protected.

See [authentication-flow guidance](docs/authentication-flow.md).

## Runtime and security behavior

- The browser loads the exact entry script `https://turing.captcha.qcloud.com/TJCaptcha.js`; that provider script may load dynamic scripts from the exact origin `https://turing.captcha.gtimg.com`. The challenge submits only `ticket` and `randstr` to Keycloak's exact `url.loginAction`.
- The server sends `Ticket`, `Randstr`, `UserIp`, `CaptchaAppId`, `AppSecretKey`, and fixed `CaptchaType=9` to `https://captcha.tencentcloudapi.com` with action `DescribeCaptchaResult`, API version `2019-07-22`.
- `TENCENT_SECRET_ID` is transmitted in the TC3 `Authorization` header as the `Credential=` identifier, followed by its credential scope. `TENCENT_SECRET_KEY` is used only by the local HMAC key-derivation and request-signing chain and is never transmitted.
- Verification succeeds only for an integral `CaptchaCode == 1`. Missing configuration, invalid input, `trerror_*`, timeouts, transport errors, non-2xx responses, API errors, malformed responses, and every other code are fail-closed. There is no fail-open option and no automatic proof retry. The browser rejects a `trerror_*` disaster ticket before submission; its optional diagnostic contains only `category: "disaster-ticket"` and an integral numeric `errorCode` (or `null`), never `ticket`, `randstr`, or `errorMessage`.
- On only the CAPTCHA response, the provider preserves the realm browser security headers and adds a fresh nonce to `script-src`. It adds `https://turing.captcha.qcloud.com` to `script-src`, `frame-src`, and `connect-src`, while the script-only origin `https://turing.captcha.gtimg.com` is added exclusively to `script-src`. The derived challenge policy adds `worker-src 'self' blob:` when that directive is absent; if it already exists, its sources are preserved and only `'self'` and `blob:` are added so the Tencent challenge can create its blob-backed worker. When both `script-src` and `default-src` are absent, the derived directive also adds `'self'` so same-origin login-theme modules remain loadable; an explicit `script-src` or `default-src` remains authoritative. A policy containing `*`, `'unsafe-inline'`, `'unsafe-eval'`, or duplicate directives is rejected rather than weakened. Realm-wide CSP is not mutated.

Privacy disclosure: the user's browser loads and executes Tencent-hosted code. The Keycloak server sends CAPTCHA proof fields and the client IP to Tencent for verification. Review Tencent Cloud's terms, privacy documentation, retention, and regional processing for your deployment, and update your own user-facing privacy notice before enabling the flow.

For the complete threat boundaries, logging rules, exact CSP behavior, and network destinations, read the [security model](docs/security-model.md).

## Upgrade, rollback, and uninstall

The upgrade, rollback, uninstall, and troubleshooting procedures below are part of the operator runbook.

Before any lifecycle change, export Keycloak configuration using your normal tested backup procedure, retain the previous verified JAR, and test the copied flow in staging.

- **Upgrade:** stop Keycloak; replace the JAR with the new verified version; run `/opt/keycloak/bin/kc.sh build`; start with `--optimized`; confirm provider discovery, CSP, and a protected login.
- **Rollback:** stop Keycloak; restore the previous JAR; run `/opt/keycloak/bin/kc.sh build`; restart; repeat the smoke checks. Do not mix provider versions.
- **Uninstall:** use this order so no running flow or container loses a required provider or secret:
  1. Bind a Browser Flow that does not reference `tencent-captcha` (or remove the execution), test a fresh login, and confirm no realm binding still references the execution.
  2. Stop the Keycloak workload. For the provided Docker Compose example, stop and remove its containers and network before touching the bind-mounted file: `docker compose -f examples/docker-compose/compose.yaml down --remove-orphans`.
  3. Remove the restricted runtime secret and provider JAR, and remove the secret-file path setting and bind-mount configuration from the deployed service definition. Never delete the mounted secret while a container is still running.
  4. For a distribution installation that will continue running, execute `/opt/keycloak/bin/kc.sh build`, restart without the provider/path/mount settings, and verify ordinary login and administration.
  5. Only after every deployment using these credentials is stopped or migrated, revoke the dedicated Tencent API identity. Retire the CAPTCHA application key only after confirming that it has no other consumer.

Retaining a flow reference to a removed provider can break authentication administration. Revoking the Tencent identity before the protected flow is detached or the workload is stopped causes avoidable fail-closed login outages.

## Troubleshooting

- **Keycloak starts but protected login returns service unavailable:** verify the resolved secret path, regular-file and parent metadata, ownership, exact schema, UTF-8/LF encoding, and `0400`/`0600` mode. Secrets are loaded lazily, so unrelated realms can remain ready.
- **Provider is absent in the Admin Console:** verify the JAR is under `/opt/keycloak/providers/`, run `kc.sh build` again, and inspect startup logs for provider-loading or split-package errors.
- **CAPTCHA script or frame is blocked:** allow the exact Tencent origin in outbound/browser network policy. Do not add wildcard CSP, `'unsafe-inline'`, or `'unsafe-eval'`.
- **Every proof is rejected:** confirm the CAPTCHA application's ID/key pair, CAM permission for `DescribeCaptchaResult`, server time, HTTPS access to the Tencent API, and trusted proxy configuration. Do not log proof or credential values.
- **Tencent receives the proxy IP:** configure Keycloak's supported proxy-header option and restrict trusted proxies to the actual proxy addresses; never trust forwarding headers from arbitrary clients.

## Project documents

- [Configuration](docs/configuration.md)
- [Authentication flow](docs/authentication-flow.md)
- [Security model](docs/security-model.md)
- [Compatibility](docs/compatibility.md)
- [Security reporting](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

Licensed under Apache-2.0. Contributions are accepted under the repository's Apache License 2.0 terms.
