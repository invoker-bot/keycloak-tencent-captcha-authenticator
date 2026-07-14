# Keycloak Tencent CAPTCHA Authenticator Design

**Date:** 2026-07-13
**Status:** Approved for implementation planning
**Repository:** `invoker-bot/keycloak-tencent-captcha-authenticator`
**Initial release:** `0.1.0`

## Purpose

Build an unofficial, reusable Keycloak authentication extension that adds Tencent Cloud CAPTCHA as a standalone `REQUIRED` Browser Flow execution. The extension must be installable as one JAR and must work without a particular application, infrastructure platform, secret store, or third-party Keycloak theme.

The initial compatibility baseline is Keycloak `26.7.0` on Java `21`. Compatibility with other Keycloak releases is not claimed until each release passes the same container-level acceptance suite.

## Public identity and licensing

- GitHub repository: `invoker-bot/keycloak-tencent-captcha-authenticator`.
- Maven group ID: `io.github.invoker-bot`.
- Java package: `io.github.invokerbot.keycloak.tencentcaptcha`.
- Artifact ID: `keycloak-tencent-captcha-authenticator`.
- Initial version: `0.1.0`.
- License: Apache License 2.0.
- The repository will include a narrowly scoped Apache `NOTICE` file for required legal and third-party attribution.
- The README files will state that this is not an official Tencent, Tencent Cloud, or Keycloak project and will identify Tencent and Keycloak names and marks as belonging to their respective owners.

The new repository will start with a clean history. It will not import the source deployment repository's history, operational records, internal hostnames, secret-store paths, or production configuration.

## Architecture

The release artifact is a single thin JAR containing:

1. A Keycloak `Authenticator` and `AuthenticatorFactory` registered through `META-INF/services/org.keycloak.authentication.AuthenticatorFactory`.
2. Tencent Cloud request construction, TC3-HMAC-SHA256 signing, response parsing, and verification logic.
3. Short-lived `aidEncrypted` generation required by the Tencent browser integration.
4. A generic `tencent-captcha.ftl` template under Keycloak theme resources.
5. Local JavaScript and CSS required by that template.
6. English and Simplified Chinese message bundles.

The provider ID is permanently `tencent-captcha`. The factory exposes only the `REQUIRED` execution requirement and does not require per-user setup. The authenticator may be inserted into any copied Browser Flow, but the documentation recommends placing it inside the Forms subflow immediately before Username Password Form. A successful SSO Cookie execution therefore bypasses a fresh CAPTCHA, while a fresh username/password authentication encounters the CAPTCHA first.

The generic template is the default implementation. A realm theme may override `tencent-captcha.ftl` while preserving the documented template attributes and POST fields. Keycloakify-Shadcn code is not included in the initial release.

## Stable public contract

The `0.1.x` compatibility contract consists of:

- Provider ID: `tencent-captcha`.
- Secret path setting: environment variable or JVM system property `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE`.
- Default secret path: `/run/secrets/tencent-captcha.env`.
- Exact secret file key set; ordering is not significant:
  - `CAPTCHA_APP_ID`
  - `CAPTCHA_APP_SECRET_KEY`
  - `TENCENT_SECRET_ID`
  - `TENCENT_SECRET_KEY`
- Template name: `tencent-captcha.ftl`.
- Template attributes:
  - `captchaAppId`
  - `aidEncrypted`
  - `captchaScriptUrl`
  - `cspNonce`
  - Keycloak's standard `url.loginAction`
- Form POST fields: `ticket` and `randstr`.
- Verification success condition: Tencent response `CaptchaCode == 1`.
- Fail-closed behavior for all invalid, unavailable, or ambiguous outcomes.

Cryptographic helpers, secret parsing internals, HTTP implementation classes, and result records are not public Java library APIs. They remain package-private where practical. The supported integration point is the Keycloak provider and its documented runtime contract.

## Secret handling

`CAPTCHA_APP_ID` is a public browser identifier. The other three values are server-side secrets. All four values share one restricted runtime file so the provider can validate an exact schema and rotate the set atomically.

The provider accepts only a regular, non-symlink secret file with mode `0400` or `0600`. Its immediate parent must be an actual directory rather than a symbolic link and must not be writable by group or other users. Validation uses no-follow filesystem metadata for the file and immediate parent. The file must be readable by the Keycloak runtime user, contain valid UTF-8 with LF line endings, contain exactly one non-empty value for every allowed key, contain no unknown keys, and be no larger than 65,536 bytes.

The JVM system property takes precedence over the environment variable. Each value is stripped; a missing or blank higher-precedence value falls through to the next source, and a missing or blank environment value falls through to `/run/secrets/tencent-captcha.env`.

The Linux Docker Compose example uses a read-only host bind mount, not Compose interpolation, `env_file`, or a top-level Compose secret backed by a host file. Its setup instructions create `examples/docker-compose/secrets/` as mode `0700`, install the runtime file as owner UID `1000`, group GID `0`, and mode `0400`, and verify those values before `docker compose up`. UID `1000` is the declared runtime UID of the pinned Keycloak image in the example; users of another image must substitute that image's runtime UID. The example is acceptance-tested on Linux because Docker Desktop and non-POSIX hosts cannot guarantee identical host-file ownership semantics.

Secrets are never accepted through Realm attributes, Authenticator Config, command-line arguments, the JAR, a Docker image layer, or documented container environment variables. The environment/system property carries only the non-secret file path. Secret values, tickets, `randstr`, authorization headers, and raw Tencent response bodies must never appear in logs or exception messages.

The factory and Keycloak startup do not read the secret file. A missing or invalid file therefore does not prevent Keycloak from starting or make unrelated realms unavailable. When an authentication reaches the CAPTCHA execution, missing or invalid configuration produces a generic service-unavailable challenge and rejects the authentication.

## Browser challenge

The template renders an accessible CAPTCHA action using the active Keycloak login theme's common layout and messages. It loads exactly the entry script `https://turing.captcha.qcloud.com/TJCaptcha.js` and rejects every other entry-script URL. Tencent's provider script may load dynamic scripts from the exact origin `https://turing.captcha.gtimg.com`; the extension does not vendor Tencent's scripts.

Each challenge creates:

- A new cryptographically random 16-byte IV for `aidEncrypted`.
- A new cryptographically random 16-byte CSP nonce.
- An `aidEncrypted` value valid for exactly 300 seconds.

`aidEncrypted` follows one reproducible protocol:

1. Encode `CAPTCHA_APP_SECRET_KEY` as UTF-8, reject an empty byte sequence, and derive a 32-byte AES key by cyclically repeating or truncating those bytes.
2. Encode the plaintext `CaptchaAppId&unixEpochSeconds&300` as UTF-8.
3. Encrypt it with AES-256-CBC and PKCS#7 padding (Java `AES/CBC/PKCS5Padding`) using the challenge's random 16-byte IV.
4. Concatenate `IV || ciphertext` and encode the result with standard padded Base64.
5. Pass it to Tencent CAPTCHA as the `aidEncrypted` option. The initial release does not send an `aidEncryptedType` option.

Tests include fixed plaintext/key/IV/output vectors and reject invalid IV or TTL values.

The client script submits only to the exact Keycloak `url.loginAction`. It submits `ticket` and `randstr` only after the Tencent callback reports success. Closing the CAPTCHA, receiving an empty result, script load failure, duplicate activation, or a `trerror_*` result does not submit a proof. For a `trerror_*` result, an optional diagnostic contains exactly the fixed `disaster-ticket` category and an integral numeric `errorCode` or `null`; it never contains proof fields or provider messages, and diagnostic failure cannot bypass rejection.

## Content Security Policy

The authenticator modifies CSP only on its own challenge response. It copies the realm browser security headers, adds a request-scoped nonce, and adds `https://turing.captcha.qcloud.com` to `script-src`, `frame-src`, and `connect-src`. It also adds the exact script-only origin `https://turing.captcha.gtimg.com` to `script-src`, never to `frame-src` or `connect-src`. The derived challenge policy adds `'self'` and `blob:` to `worker-src` so Tencent can create its blob-backed worker; when the directive is absent this yields exactly `worker-src 'self' blob:`, and when it exists its sources are preserved. If the realm policy defines neither `script-src` nor `default-src`, the derived `script-src` adds `'self'` so the active same-origin login-theme module graph remains executable; explicit directives remain authoritative. It rejects policies that require wildcard sources, `unsafe-eval`, or new `unsafe-inline` behavior.

The provider must not mutate the realm-wide CSP. The implementation copies all realm browser headers onto the challenge response, sets the challenge CSP, and invokes Keycloak's request-scoped `SecurityHeadersProvider.options().skipHeaders()` only while building that response so the response filter cannot overwrite it. It retains no cross-request state. Ordinary login, registration, error, master-realm, and account-console pages must preserve their original realm headers byte-for-byte.

## Server-side verification

The verifier calls the fixed HTTPS endpoint `https://captcha.tencentcloudapi.com` using action `DescribeCaptchaResult`, API version `2019-07-22`, service `captcha`, and `CaptchaType=9`.

The request includes only:

- `Ticket`
- `Randstr`
- `UserIp`
- `CaptchaAppId`
- `AppSecretKey`
- `CaptchaType`

Tencent Cloud API authentication uses TC3-HMAC-SHA256 with `TENCENT_SECRET_ID` and `TENCENT_SECRET_KEY`. The implementation uses Java's standard `HttpClient`; it does not ship a Tencent SDK or a second copy of libraries supplied by Keycloak.

The connection timeout is three seconds and the whole request timeout is five seconds. A proof is never retried automatically. `ticket` is limited to 8,192 UTF-16 code units, `randstr` to 1,024, and the client-IP string to 255. Empty or oversized proof fields, `trerror_*` tickets, transport failures, timeouts, non-2xx responses, JSON errors, Tencent API errors, missing or non-integral `CaptchaCode`, and every code other than `1` are rejected before any success is reported.

The client IP is obtained from Keycloak's connection context. Documentation must require correct Keycloak proxy-header and trusted-proxy configuration; otherwise Tencent may receive the reverse proxy address instead of the browser address.

## Error handling and observability

Users receive localized generic messages for configuration unavailable, verification rejected, and browser-script failure. Messages do not expose provider response details.

Server logs use bounded categories and a one-way, truncated correlation identifier derived from Keycloak authentication-session identifiers. They do not log raw session identifiers, user credentials, CAPTCHA proofs, secrets, authorization data, IP addresses, or raw provider responses.

The initial release has no fail-open option. Operators cannot configure API failures or missing configuration to bypass CAPTCHA.

## Repository structure

```text
.
├── .github/
│   ├── ISSUE_TEMPLATE/
│   ├── workflows/
│   │   ├── ci.yml
│   │   ├── codeql.yml
│   │   ├── release.yml
│   │   └── secret-scan.yml
│   ├── dependabot.yml
│   └── pull_request_template.md
├── docs/
│   ├── authentication-flow.md
│   ├── compatibility.md
│   ├── configuration.md
│   ├── security-model.md
│   └── superpowers/
│       ├── plans/
│       └── specs/
├── examples/
│   └── docker-compose/
│       ├── Dockerfile
│       ├── compose.yaml
│       └── secrets/tencent-captcha.env.example
├── src/
│   ├── main/java/io/github/invokerbot/keycloak/tencentcaptcha/
│   ├── main/resources/META-INF/services/
│   ├── main/resources/theme-resources/templates/
│   ├── main/resources/theme-resources/resources/
│   ├── main/resources/theme-resources/messages/
│   ├── test/java/
│   └── test/js/
├── .editorconfig
├── .gitignore
├── CHANGELOG.md
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE
├── NOTICE
├── package-lock.json
├── package.json
├── README.md
├── README.zh-CN.md
├── SECURITY.md
├── mvnw
└── pom.xml
```

## Documentation

`README.md` is the primary English entry point and links to `README.zh-CN.md`. Together with focused documents, they cover:

- Purpose and non-goals.
- Keycloak/Java compatibility matrix.
- JAR installation and `kc.sh build` requirements.
- Docker Compose installation.
- Tencent Cloud application and least-privilege CAM prerequisites.
- Secret-file schema, ownership, permissions, and rotation.
- Admin Console steps to copy a Browser Flow, add the provider, order it, and bind it.
- SSO Cookie behavior and scopes intentionally not protected unless separately configured.
- Reverse-proxy and client-IP behavior.
- CSP behavior and network destinations.
- Fail-closed semantics.
- Upgrade, rollback, uninstall, and troubleshooting procedures.
- Privacy disclosure: the browser loads Tencent code, and the server sends CAPTCHA proof fields and client IP to Tencent.
- Non-official project and trademark notices.
- The complete Maven coordinate `io.github.invoker-bot:keycloak-tencent-captcha-authenticator:0.1.0`, explicitly distinguishing it from the Java package name.

Examples contain synthetic values only. They never contain real credentials, internal hostnames, secret-store paths, or production realm exports.

## Testing

Development follows test-first red-green-refactor cycles. Unit tests cover:

- Exact secret parsing, permissions, file type, parent-directory trust, size limits, and redaction.
- `aidEncrypted` fixed vectors and validity bounds.
- TC3 signing fixed vectors.
- Exact Tencent request fields and headers.
- Three-second connection and five-second request timeouts.
- Successful response, every rejected response class, invalid JSON, API error, transport error, timeout, and interrupted request.
- Empty and oversized `ticket`, `randstr`, and IP values.
- Factory metadata, ServiceLoader registration, and lazy secret loading.
- Authenticator challenge, action, fail-closed behavior, session correlation redaction, and no retained state.
- CSP nonce creation, precise origin allowlist, and rejection of unsafe policies.
- Browser script success, cancellation, empty result, duplicate click, script error, exact action target, and proof-field handling.

Client-script tests use Node.js `20` and the built-in `node:test` runner with no runtime npm dependencies. `package.json`, a committed `package-lock.json`, and `src/test/js/*.test.mjs` define the exact `npm test` entry point.

Container acceptance tests use a fresh Keycloak `26.7.0` image and prove:

- The JAR installs and an optimized build starts.
- Keycloak discovers provider ID `tencent-captcha`.
- The embedded template renders without another theme artifact.
- A copied flow can add the execution as `REQUIRED`.
- Missing and invalid secrets reject the protected flow without preventing Keycloak readiness.
- The final challenge has the expected CSP nonce, both exact Tencent script origins in `script-src`, no `https://turing.captcha.gtimg.com` source in `frame-src` or `connect-src`, and request-scoped `worker-src 'self' blob:` when the realm policy has no worker directive.
- The final HTML references exactly `https://turing.captcha.qcloud.com/TJCaptcha.js` and passes only `aidEncrypted` to its constructor options.
- Ordinary login, registration, error, master-realm, and account-console responses retain the same browser security headers before and after the CAPTCHA challenge.
- The Docker Compose example starts on Linux with a host bind-mounted secret whose owner is Keycloak UID `1000`, whose parent is mode `0700`, and whose file mode is `0400` or `0600`.

CI never requires production Tencent credentials. Live Tencent verification is an operator acceptance step and is not run for pull requests from forks.

## CI and supply chain

Pull requests and pushes to `main` run Maven verification, `npm test`, the Keycloak container smoke suite, an advanced CodeQL workflow, and a pinned Gitleaks workflow. Dependabot covers Maven, npm, and GitHub Actions weekly. Workflow permissions are least-privilege, and third-party actions are pinned to immutable commit SHAs. GitHub's platform secret scanning and push protection are repository settings in addition to, not substitutes for, the pull-request Gitleaks workflow.

Tags matching `v*` run the complete verification suite and publish a GitHub Release containing:

- The thin provider JAR.
- `SHA256SUMS`.
- A CycloneDX SBOM.
- Generated release notes.
- GitHub artifact attestation/build provenance.

The initial release does not publish a Docker image. The example Dockerfile demonstrates how downstream users add the JAR to their chosen Keycloak image.

## GitHub repository settings

The repository is public with `main` as its default branch. After the initial push, repository security settings enable available Dependabot alerts and security updates, secret scanning, and push protection. CodeQL uses the committed advanced `codeql.yml` workflow rather than GitHub default setup. Main-branch protection requires the established CI checks and prevents force pushes.

## Extraction from the source deployment

The implementation is ported selectively into the clean repository and rewritten under the new package. Application-specific initialization, deployment helpers, secret rendering, production domains, third-party theme patches, and migration reports are excluded.

Before any public push, the complete new working tree and its clean history are scanned for secrets and internal identifiers. Existing production deployments continue to use their verified artifact until a separately reviewed upgrade explicitly consumes a tagged release from this repository.

## Acceptance criteria

The `0.1.0` milestone is complete only when:

1. The full unit and browser-script suite passes on Java 21.
2. A fresh Keycloak 26.7.0 optimized container discovers the provider and renders the embedded page.
3. Missing/invalid configuration and all Tencent/API failures are proven fail-closed.
4. No application-specific code, internal infrastructure identifier, production hostname, or real secret exists in the repository or history.
5. English and Chinese installation, configuration, security, and troubleshooting documentation is complete.
6. CI, CodeQL, Dependabot, release automation, SBOM, checksums, and attestation are configured.
7. Spec-compliance review, code-quality review, and a final cross-module review report no unresolved Critical or Important findings.
8. The public GitHub repository is created under `invoker-bot`, the verified `main` commit is pushed, and the `v0.1.0` release artifacts are published.
