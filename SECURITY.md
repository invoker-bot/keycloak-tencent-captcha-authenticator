# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| Current `0.1.x` release | Yes |
| Unreleased branches and older lines | No guaranteed support |

Security and correctness fixes target the current `0.1.x` line. Support changes will be announced in [CHANGELOG.md](CHANGELOG.md) and [compatibility documentation](docs/compatibility.md).

## Report a vulnerability

Do not open a public issue, discussion, pull request, or build log for a suspected vulnerability.

Use GitHub private vulnerability reporting for this repository:

1. Open the repository's **Security** tab.
2. Select **Advisories**.
3. Select **Report a vulnerability** to create a private draft security advisory.

Direct link: [privately report a vulnerability](https://github.com/invoker-bot/keycloak-tencent-captcha-authenticator/security/advisories/new).

If GitHub does not show that control, do not publish exploit or secret material. Use GitHub's repository owner contact surface to request that private reporting be enabled, without including vulnerability details.

Include only the minimum information needed to reproduce safely:

- affected provider, Keycloak, Java, browser, and deployment versions;
- impact and the authentication path involved;
- reproducible steps or a minimal proof of concept using synthetic values;
- whether the issue is already public or actively exploited;
- suggested mitigation, if known.

Never include Tencent credentials, CAPTCHA `ticket` or `randstr`, authorization headers, raw provider responses, production hostnames, Realm exports, user data, logs containing session identifiers, or live client IP addresses. Replace sensitive values with angle-bracket placeholders.

Maintainers will acknowledge through the private advisory, triage impact, coordinate a fix and disclosure, and credit reporters who request attribution. Response timing is best-effort; no service-level deadline is promised. Please allow a reasonable remediation window before public disclosure.

## Scope notes

The provider is fail-closed and has no fail-open configuration. Reports about bypass, CSP isolation, secret-file validation, proof verification, proxy-derived `UserIp`, sensitive logging, dependency integrity, or authentication-flow placement are in scope. General Keycloak or Tencent Cloud service issues should be reported to those projects through their official channels unless this extension creates or worsens the issue.
