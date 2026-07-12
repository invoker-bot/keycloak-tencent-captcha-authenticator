# Compatibility

## Supported matrix

| Provider release | Keycloak server | Java runtime | Container acceptance | Support status |
| --- | --- | --- | --- | --- |
| `0.1.x` | `26.7.0` | `21` | `quay.io/keycloak/keycloak:26.7.0` | Supported baseline |
| `0.1.x` | Any other Keycloak | Any other Java | Not established | Unsupported / no compatibility claim |

The project is compiled with Java release 21. The official Keycloak 26.7.0 image also uses Java 21. A newer JDK may build the source when Maven Enforcer permits it, but production compatibility remains the matrix above.

Compatibility with a later Keycloak patch, minor, or major release is not implied by semantic similarity. Keycloak's private server SPI and theme behavior can change. A version is added only after the full unit, browser-script, packaging, and fresh-container acceptance suite passes against that exact image.

## Stable `0.1.x` runtime contract

The following identifiers and behaviors are intended to remain stable throughout `0.1.x`:

- Maven coordinate prefix `io.github.invoker-bot:keycloak-tencent-captcha-authenticator`;
- provider ID `tencent-captcha`;
- requirement choice `REQUIRED` only;
- path setting `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE` and default `/run/secrets/tencent-captcha.env`;
- secret keys `CAPTCHA_APP_ID`, `CAPTCHA_APP_SECRET_KEY`, `TENCENT_SECRET_ID`, and `TENCENT_SECRET_KEY`;
- template `tencent-captcha.ftl`;
- template attributes `captchaAppId`, `aidEncrypted`, `captchaScriptUrl`, `cspNonce`, and Keycloak `url.loginAction`;
- POST fields `ticket` and `randstr`;
- exact Tencent destinations and `DescribeCaptchaResult` API version `2019-07-22`;
- success only for integral `CaptchaCode == 1` and fail-closed behavior otherwise.

Package-private cryptographic, HTTP, parsing, and result classes are implementation details, not a public Java library API.

## Theme compatibility

The bundled template uses Keycloak's common login layout and is acceptance-tested without any external theme. A custom login theme may override `tencent-captcha.ftl` only if it preserves the documented attributes, action target, POST fields, nonce, and security behavior. No Keycloakify or other theme artifact is included or required.

## Upgrade evaluation

Before changing Keycloak or Java:

1. build with `./mvnw -B verify`;
2. run `npm test` explicitly if JavaScript tests are being isolated;
3. on Linux with Docker available, run `python3 tests/integration/run.py` within its built-in ten-minute bound;
4. verify provider discovery, copied-flow placement, embedded template, missing/invalid secret fail-closed behavior, exact CSP, exact script URL, and ordinary-page header isolation;
5. perform an operator-controlled live Tencent verification with non-production test credentials outside pull-request CI.

Do not report a new combination as supported from compilation alone.

## Support window

Security and correctness fixes are accepted for the current `0.1.x` line. The project does not promise backports after a later minor line supersedes it; any end-of-support announcement will be recorded in `CHANGELOG.md` and this matrix. See [SECURITY.md](../SECURITY.md) for vulnerability handling.
