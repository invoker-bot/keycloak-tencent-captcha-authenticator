# Security model

This document describes the `0.1.x` trust boundaries and fail-closed behavior. It is not a substitute for a deployment-specific threat model.

## Trust boundaries and privacy data flow

1. Keycloak renders the bundled `tencent-captcha.ftl` challenge with the public `CaptchaAppId`, a fresh 300-second `aidEncrypted`, a fresh 16-byte-derived CSP nonce, the local client-module URL, and the exact Tencent script URL.
2. The user's browser loads and executes `https://turing.captcha.qcloud.com/TJCaptcha.js` from Tencent.
3. On Tencent callback success, the local module posts only `ticket` and `randstr` to Keycloak's exact `url.loginAction`.
4. Keycloak obtains the client IP from its connection context and sends `Ticket`, `Randstr`, `UserIp`, `CaptchaAppId`, `AppSecretKey`, and `CaptchaType=9` in the HTTPS request body to `https://captcha.tencentcloudapi.com` using `DescribeCaptchaResult` API version `2019-07-22`.
5. `TENCENT_SECRET_ID` is transmitted in the TC3 `Authorization` header as the `Credential=` identifier, followed by its credential scope. `TENCENT_SECRET_KEY` is used only by the local HMAC key-derivation and request-signing chain and is never transmitted.
6. Keycloak accepts only an integral `CaptchaCode == 1`.

Privacy impact: Tencent-hosted code executes in the user's login browser. CAPTCHA proof fields, the public application ID, its server verification key, the client IP, and the Tencent API identity identifier are transmitted to Tencent. The Tencent API signing key is not transmitted. Operators must review Tencent Cloud's applicable terms, privacy and regional-processing documentation, define retention/account controls, and disclose this processing to users as required.

## Client IP and trusted proxies

`UserIp` comes from Keycloak's connection context. Deployments behind a reverse proxy must use the Keycloak version's supported proxy-header setting and explicitly restrict trusted proxy addresses. If proxy handling is absent, Tencent may receive the proxy IP; if forwarding headers are trusted from arbitrary clients, an attacker may influence `UserIp`.

Test the effective address through the full load-balancer path without logging the IP. Tencent states that both CAPTCHA domains use dynamic IP addressing; permit the exact hostnames in egress controls rather than fixed proxy IP lists.

## Exact network destinations

| Initiator | Destination | Purpose |
| --- | --- | --- |
| User browser | `https://turing.captcha.qcloud.com/TJCaptcha.js` | Load TJCaptcha and its same-origin browser resources |
| Keycloak server | `https://captcha.tencentcloudapi.com` | POST TC3-authenticated `DescribeCaptchaResult` verification |

No alternate CAPTCHA script or API host is configurable. DNS, TLS trust, and outbound controls remain the operator's responsibility.

## Exact CSP behavior

The provider changes headers only on its own CAPTCHA challenge response. It copies every configured realm browser security header (or Keycloak's default for a missing header), then derives one challenge CSP:

- existing directives and sources are preserved in order;
- `script-src` receives `'nonce-<request nonce>'` and `https://turing.captcha.qcloud.com`;
- `frame-src` receives `https://turing.captcha.qcloud.com`;
- `connect-src` receives `https://turing.captcha.qcloud.com`;
- if `script-src` is absent it inherits `default-src`; if `frame-src` is absent it inherits `child-src`, then `default-src`; if `connect-src` is absent it inherits `default-src`;
- the realm-wide CSP is never mutated and the provider retains no cross-request header state.

The challenge is rejected if the base CSP is blank or ambiguous, has a duplicate directive, or contains any `*`, `'unsafe-inline'`, or `'unsafe-eval'` source. The provider never introduces wildcard sources, `unsafe-inline`, or `unsafe-eval`. Ordinary login, registration, error, master-realm, and account-console pages retain their normal realm headers.

## Secret trust model

Only `CAPTCHA_APP_ID` is public. The other three configured values are server-side secrets. The provider accepts them only from one regular, non-symlink file with exact schema, size at most 65,536 bytes, UTF-8/LF content, and mode `0400` or `0600`. The immediate parent must be an actual directory and cannot be writable by group or other users.

The file-path setting `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE` may be a JVM property or environment variable; the secret values themselves are never accepted there. Secrets are loaded lazily at authentication, not factory startup. This contains configuration failure to protected executions while keeping unrelated Keycloak readiness available.

Secret values, `ticket`, `randstr`, authorization headers, raw Tencent responses, raw authentication-session identifiers, and IP addresses must not be logged. User-visible failures are generic and localized. Server result categories and one-way truncated correlations are bounded and contain no raw identifier.

## `aidEncrypted`

Each challenge uses a fresh random 16-byte IV and the fixed plaintext `CaptchaAppId&unixEpochSeconds&300`. The UTF-8 AppSecretKey bytes are cyclically repeated or truncated to 32 bytes; encryption is AES-256-CBC with PKCS#7-compatible padding; output is padded Base64 of `IV || ciphertext`. `aidEncryptedType` is omitted, selecting Tencent's documented CBC default.

`aidEncrypted` reduces unauthorized use of a leaked public app ID but does not replace server-side proof verification. The browser necessarily receives the encrypted value; it never receives the AppSecretKey.

## Verification and fail-closed matrix

The server uses a three-second connect timeout and five-second whole-request timeout and never retries a proof. It rejects before sending if `ticket`, `randstr`, or IP is blank; if their UTF-16 lengths exceed 8,192, 1,024, or 255; or if the ticket starts with `trerror_`.

The outcome is fail-closed for:

- missing or invalid secret configuration;
- empty, oversized, disaster, or malformed proof input;
- DNS, TLS, connection, interruption, timeout, or other transport failures;
- non-2xx HTTP responses;
- invalid JSON, missing response objects, Tencent API errors, or raw response ambiguity;
- absent, non-integral, out-of-range, or non-`1` `CaptchaCode`.

There is no fail-open switch. Configuration failure returns a generic service-unavailable challenge; proof rejection returns a generic verification failure and a fresh challenge.

## Concurrency and abuse controls

One non-blocking bulkhead is shared by all provider instances in a Keycloak JVM and permits at most 32 Tencent verification calls in flight. When it is full, a new proof fails closed immediately without calling Tencent. The permit is always released after the verification attempt, including exceptional or malformed verifier outcomes.

The bulkhead is node-local: every Keycloak process has its own independent limit. It is a last-resort bound on outbound work, not an IP rate limiter or a complete denial-of-service control. Deployments must still enforce request and per-IP rate limits at a trusted reverse proxy or gateway and monitor aggregate accepted, rejected, busy, transport-failure, latency, and saturation behavior using their existing platform telemetry. The provider deliberately adds no metrics dependency.

Accepted proofs are logged at `INFO`, while configuration failures are logged at `ERROR`. Proof rejection, bulkhead saturation, transport failure, and other attacker-triggerable verification outcomes are logged only at `DEBUG` to limit default log amplification. Keep production debug logging disabled except during a bounded investigation, aggregate or sample operational signals outside the provider, and never add proof, credential, IP, raw response, or session values to telemetry.

## Operational responsibilities

- Verify JAR checksums and provenance before placing third-party provider code in Keycloak. Keycloak providers run without a security sandbox.
- Use least-privilege Tencent API identities and rotate the complete secret set atomically.
- Restrict file access, Keycloak administration, flow binding, logs, diagnostics, backups, and container access.
- Monitor only bounded failure categories and aggregate rates; never add sensitive values for troubleshooting.
- Maintain recovery access before changing authentication bindings and test upgrade, rollback, and uninstall in staging.

Report suspected vulnerabilities through [private vulnerability reporting](../SECURITY.md), not a public issue.
