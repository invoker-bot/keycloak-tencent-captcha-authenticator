# Changelog

All notable changes to this project are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows Semantic Versioning for its documented compatibility contract.

## [Unreleased]

### Added

- Start CAPTCHA automatically for login and registration, including an optional adapter for existing multilingual shadcn themes. Failed checks remain on a manual retry page.
- Version the bundled browser module by its content digest so upgrades do not reuse a cached client without automatic activation.
- Verify both identity and gzip browser resources and document clearing Keycloak's generated gzip cache when upgrading or rolling back with a retained data volume.
- Support Tencent invisible verification with strict server-side risk validation: both integral `CaptchaCode == 1` and `EvilLevel == 0` are required. Missing, malformed, unknown, or malicious risk results fail closed.
- Test risk rejection and benign continuation in real Keycloak login and registration flows using a signed-request TLS fixture.

## [0.1.2] - 2026-10-22

### Fixed

- Follow the selected Keycloak language in Tencent's popup, with explicit SDK language mapping and English fallback.
- Provide an optional shadcn adapter with English and Simplified Chinese CAPTCHA text, including server-rendered errors, and document enabling the language menu.
- Keep the page title, home link, and theme controls in the selected language.
- Add an optional shadcn registration adapter that hides non-required profile fields before form validation while preserving fields required by Keycloak.
- Document and configure a separate registration flow with a top-level `REQUIRED` server-verified CAPTCHA step, including direct registration.

### Added

- Exercise language switching and locale retention through server-verified login and direct registration in the disposable TLS integration suite.
- Cover missing, forged, disaster, and replayed CAPTCHA proofs, authentication-flow tampering, and successful registration with a signed-request TLS fixture.
- Refresh the English and Chinese README previews with actual v0.1.2 browser captures and visible language selection.

## [0.1.1] - 2026-10-21

### Added

- Optional asynchronous Sentry diagnostics configured through a local `.env` at build time or runtime environment variables, with sampled, bounded events that exclude credentials and proof data.
- Sanitized Tencent API error codes and request IDs for diagnosing server-side verification failures, including invalid API credentials.
- Task commands for building, testing, formatting, container acceptance, and release artifact generation.

### Fixed

- Allow Tencent's exact dynamic-script origin `https://turing.captcha.gtimg.com` in `script-src` without broadening `connect-src` or `frame-src`.
- Preserve same-origin login-theme module loading by adding `'self'` when the realm policy defines neither `script-src` nor `default-src`, while retaining explicit administrator restrictions.
- Reject `trerror_*` disaster tickets in the browser and expose only the fixed diagnostic category plus an integral numeric error code or `null`, without proof fields or provider messages.
- Keep CodeQL initialization and analysis on the same pinned version, with grouped dependency updates and a regression check.

### Changed

- Update reviewed Maven build plugins, JUnit, Mockito, and pinned GitHub Actions, including artifact provenance attestation. The supported runtime remains Keycloak 26.7.0 on Java 21.

## [0.1.0] - 2026-07-13

### Added

- Standalone `tencent-captcha` Keycloak authenticator for a copied Browser Flow.
- Thin Java 21 provider JAR targeting Keycloak 26.7.0.
- Lazy, exact-schema, restricted-file secret loading with atomic Linux Docker Compose preparation.
- Tencent `aidEncrypted` generation, TC3-HMAC-SHA256 signing, and fail-closed `DescribeCaptchaResult` verification.
- Embedded English and Simplified Chinese challenge template, JavaScript, CSS, and localized messages.
- Request-scoped CSP nonce and exact Tencent-origin handling without realm-wide header mutation.
- Unit, browser-script, packaging, Python harness, and fresh-Keycloak container acceptance coverage.
- English and Chinese installation, configuration, authentication-flow, security, compatibility, lifecycle, and troubleshooting documentation.
- Public security, contribution, issue, and pull-request policies for the Apache-2.0 project.
- Separately attributed Contributor Covenant 2.1 conduct policy under CC BY 4.0.

[Unreleased]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/releases/tag/v0.1.2
[0.1.1]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/releases/tag/v0.1.1
[0.1.0]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/releases/tag/v0.1.0
