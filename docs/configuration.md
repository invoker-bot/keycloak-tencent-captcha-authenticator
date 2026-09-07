# Configuration

This guide is the complete runtime configuration contract for `keycloak-tencent-captcha-authenticator` `0.1.x`.

## Tencent prerequisites

Create a Web/App application in the Tencent Cloud CAPTCHA console. Record its decimal `CaptchaAppId` and server-side `AppSecretKey`. Create a separate Tencent Cloud API identity for verification and, where the account's CAPTCHA authorization model permits action-level authorization, grant only:

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": ["captcha:DescribeCaptchaResult"],
      "resource": ["*"]
    }
  ]
}
```

Validate the policy for your account before deployment. The provider uses TC3-HMAC-SHA256 with the Tencent API identity; it does not bundle a Tencent SDK. Official references: [CAM policy elements](https://cloud.tencent.com/document/product/598/10603) and [`DescribeCaptchaResult`](https://cloud.tencent.com/document/api/1110/36926).

## Exact secret schema

Create one restricted runtime file with exactly these four assignments in any order:

```dotenv
CAPTCHA_APP_ID=<CAPTCHA_APP_ID>
CAPTCHA_APP_SECRET_KEY=<CAPTCHA_APP_SECRET_KEY>
TENCENT_SECRET_ID=<TENCENT_SECRET_ID>
TENCENT_SECRET_KEY=<TENCENT_SECRET_KEY>
```

The parser requires:

- exactly one non-empty value for each allowed key and no unknown keys;
- a decimal `CAPTCHA_APP_ID`;
- valid UTF-8 and LF line endings (CRLF is rejected);
- a maximum file size of 65,536 bytes;
- a regular, non-symlink file with mode `0400` or `0600`;
- an immediate parent that is a real directory, not a symlink, and is not writable by group or other users.

The file must be readable by the Keycloak runtime user. Mode `0400`, owner equal to the runtime UID, and a `0700` parent are recommended.

`CAPTCHA_APP_ID` is exposed to the browser. `CAPTCHA_APP_SECRET_KEY`, `TENCENT_SECRET_ID`, and `TENCENT_SECRET_KEY` are server-side secrets. Despite the first value being public, all four are kept together to validate an exact schema and rotate a consistent set atomically.

## Path selection and precedence

The setting name is `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE`. Resolution is:

1. non-blank JVM system property `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE`;
2. non-blank environment variable `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE`;
3. `/run/secrets/tencent-captcha.env`.

Leading and trailing whitespace around the selected path is stripped. The setting carries only a file path, never credential values.

For a distribution installation, either use the default path or set a path-only JVM property according to your Keycloak service manager. For the provided container example, the default path is already a read-only bind mount.

Do not supply credential values through process environment variables, CLI arguments, Realm attributes, Authenticator Config, Realm JSON exports, the provider JAR, a container image layer, Maven settings, or source control. The provider intentionally has no per-execution secret fields.

## Linux Docker Compose setup

The checked-in example is `examples/docker-compose/compose.yaml`. It builds the provider into the pinned `quay.io/keycloak/keycloak:26.7.0` image and executes `/opt/keycloak/bin/kc.sh build` in the builder stage.

Prepare a source file outside the checkout using the exact four-line schema, then run:

```bash
./mvnw -B verify
sudo examples/docker-compose/prepare-secret.sh /secure/input/tencent-captcha.env
stat -c '%u:%g:%a' examples/docker-compose/secrets examples/docker-compose/secrets/tencent-captcha.env
docker compose -f examples/docker-compose/compose.yaml config --quiet
docker compose -f examples/docker-compose/compose.yaml up --build --detach
```

Expected metadata:

```text
<HOST_UID>:<HOST_GID>:700  examples/docker-compose/secrets
1000:0:400  examples/docker-compose/secrets/tencent-captcha.env
```

Depending on the host, the directory owner may remain the invoking administrative user; its required property is mode `0700`. The installed file must be owner UID `1000`, group GID `0`, mode `0400`. UID `1000` is specific to the pinned image. Determine and substitute the runtime UID before using another image.

The helper is Linux-only and requires root because it assigns the container UID. It rejects symlink sources and targets, validates exact schema without echoing contents, writes a temporary file through directory descriptors, sets ownership/mode, and atomically replaces the destination. Docker Desktop and non-POSIX hosts cannot prove equivalent ownership semantics, so this example's metadata acceptance path is Linux-only.

The Compose file uses a read-only host bind mount:

```yaml
volumes:
  - "./secrets/tencent-captcha.env:/run/secrets/tencent-captcha.env:ro"
```

This is deliberate. Docker documents that environment variables can be exposed unexpectedly and that file-backed Compose inputs have a trust model that operators must review. This project does not use Compose variable interpolation, `env_file`, or a top-level file-backed Compose secret for these values because the provider requires predictable host POSIX metadata. See [Docker secrets](https://docs.docker.com/compose/how-tos/use-secrets/) and the [Compose trust model](https://docs.docker.com/compose/trust-model/).

## Rotation

The provider reads and validates the file whenever authentication reaches the execution; factory initialization does not read it. Rotate all four fields as a set:

1. Create a new restricted source file without altering the active target.
2. Confirm the new Tencent CAPTCHA application/API identity is active and has least privilege.
3. Run the provided Linux preparation helper (or an equivalent reviewed atomic installation) to replace the runtime file.
4. Start a new authentication and verify it succeeds. Existing challenges carry a five-minute `aidEncrypted`; avoid disabling the previous CAPTCHA key until the rollout window has passed.
5. Revoke the superseded API identity and CAPTCHA secret according to Tencent Cloud procedures.

Never edit the mounted file in place. An atomic replacement avoids mixed key sets, but some container bind-mount implementations retain the original inode. If the running container does not observe the replacement, recreate the Keycloak container after the atomic install.

## Optional Sentry diagnostics

Copy `.env.example` to the ignored repository-root `.env` and fill `SENTRY_DSN`
with the project's public HTTPS DSN. Use unquoted `KEY=value` assignments:

```dotenv
SENTRY_DSN=<SENTRY_DSN>
SENTRY_ENVIRONMENT=development
SENTRY_RELEASE=
```

`./mvnw -B verify` reads `.env` automatically for development and deployment
builds. Only these three Sentry settings are filtered into
`META-INF/tencent-captcha-sentry.properties` in the provider JAR. No hostname or
DSN is hardcoded in source. A build without `.env` has reporting disabled. A JAR
built with a DSN contains that event-ingestion key; build distributable releases
without your local `.env`.

At runtime, `SENTRY_DSN`, `SENTRY_ENVIRONMENT`, and `SENTRY_RELEASE` environment
variables override the corresponding build defaults. An explicitly empty runtime
`SENTRY_DSN` disables reporting even for an instrumented build. Rebuild after
changing a build-time `.env`, or recreate the service after changing its runtime
environment. Do not add Sentry settings to the exact four-line Tencent secret
file. The Sentry management API token is never needed by the provider.

The server reports API errors, transport errors, invalid responses, unavailable
CAPTCHA configuration, saturation, and rejected Tencent proofs. Events contain
only a bounded category, numeric CAPTCHA code, validated Tencent API error code
and request ID, and a hashed correlation. For example,
`api_error_code=AuthFailure.SecretIdNotFound` identifies a deleted or invalid
Tencent API identity, even if the browser completed the challenge successfully.
Check and replace `TENCENT_SECRET_ID` and `TENCENT_SECRET_KEY` together using the
rotation procedure above; do not alter the browser callback or accept failed
verification to work around a credential error.

Reporting uses the [Sentry envelope protocol](https://develop.sentry.dev/sdk/foundations/envelopes/)
with the existing Java HTTP client and Jackson dependencies. It is asynchronous,
permits at most two requests in flight, samples each of six fixed failure categories
at most once per minute per JVM, uses a three-second request timeout, and backs off
on HTTP 429. It does not retry events or persist them to disk. Sentry outages never
change authentication decisions. This is CAPTCHA server diagnostics; it does not
collect arbitrary Keycloak logs, browser exceptions, replay recordings, or user data.

## Uninstall cleanup

Detach `tencent-captcha` from every bound Browser Flow before removing configuration. Then stop the Keycloak workload; for the provided Compose example, run `docker compose -f examples/docker-compose/compose.yaml down --remove-orphans` before deleting the mounted runtime file. Remove the restricted runtime secret, the path-only `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE` setting, and the deployed bind-mount entry. Revoke the dedicated Tencent API identity only after every consumer has stopped or migrated. See the ordered [README lifecycle procedure](../README.md#upgrade-rollback-and-uninstall).

## Validation and failure behavior

Missing or invalid configuration does not prevent Keycloak startup because loading is lazy. When a protected authentication reaches the execution, configuration failure yields a localized generic HTTP 503 challenge and rejects the login. It never exposes file paths, values, or parser detail to the browser. The system is fail-closed and has no bypass setting.

To diagnose a configuration failure without exposing data, inspect only:

```bash
namei -l /run/secrets/tencent-captcha.env
stat -c '%F %U:%G %a %s' /run/secrets/tencent-captcha.env
```

Check that the parent is not a symlink or group/other writable, the target is regular and not a symlink, the runtime user can read it, size is within 65,536 bytes, and the file has four LF-only UTF-8 assignments. Do not paste its contents into an issue.
