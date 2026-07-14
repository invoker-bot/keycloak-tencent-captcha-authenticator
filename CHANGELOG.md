# Changelog

All notable changes to this project are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows Semantic Versioning for its documented compatibility contract.

## [Unreleased]

### Fixed

- Allow Tencent's exact dynamic-script origin `https://turing.captcha.gtimg.com` in `script-src` without broadening `connect-src` or `frame-src`.
- Preserve same-origin login-theme module loading by adding `'self'` when the realm policy defines neither `script-src` nor `default-src`, while retaining explicit administrator restrictions.
- Reject `trerror_*` disaster tickets in the browser and expose only the fixed diagnostic category plus an integral numeric error code or `null`, without proof fields or provider messages.

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

[Unreleased]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/releases/tag/v0.1.0
