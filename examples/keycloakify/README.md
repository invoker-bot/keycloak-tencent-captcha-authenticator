# Required registration fields in an existing shadcn theme

This optional adapter targets `keycloakify-shadcn-starter` v250004.0.24
(`57dc7f0181ef3af115c8064ac8eee854724252b3`). Apply it before building the theme JAR:

```sh
node examples/keycloakify/patch-registration.mjs /path/to/theme-source
```

It filters `profile.attributesByName` using Keycloak's resolved `required === true`
flags before `useUserProfileForm` validates or renders the registration form.
Optional first name, last name, email and custom attributes are hidden. Required
attributes, password generation and field order remain intact. Profile update,
broker review and other pages are unchanged. It does not alter realm profile
rules or provide CAPTCHA security. The adapter refuses an already patched or
incompatible source file; keep it in the theme build process.

Configure the separate [Registration Flow](../../docs/authentication-flow.md#registration-flow)
for server-side CAPTCHA enforcement. A theme alone cannot enforce verification.

## Language selection

For the same starter with a custom `src/login/pages/tencent-captcha/Page.tsx`
using `useI18n` and `createSingleFlightCaptchaController`, apply:

```sh
node examples/keycloakify/patch-multilingual.mjs /path/to/theme-source
```

This replaces misplaced CAPTCHA translations with complete English and Simplified
Chinese messages, emits static literals for Keycloakify's server-message extractor,
and passes `currentLanguage.languageTag` to the shared provider client. The client
also follows asynchronous translation loading for the browser title and localizes
the home and theme-toggle labels. The CAPTCHA client
is copied from this repository's tested module, excluding the FTL-only DOM bootstrap.
The optional third argument supplies that module's path for standalone theme builders.
Apply this after installing the custom CAPTCHA page and before building its JAR.
The patch refuses an incompatible or already patched page before writing files.

Enable **Realm settings → Localization → Internationalization**, select **English
(`en`)** and **Chinese (Simplified) (`zh-CN`)**, and set the default locale. The
starter's language menu appears when multiple locales are enabled. Keycloak's
own switch URLs preserve the authentication session through the CAPTCHA, login,
registration, and error pages. Keep CAPTCHA `REQUIRED` in both flows.

The embedded FTL theme also uses Keycloak's resolved locale. Tencent receives an
explicit `userLanguage`; unsupported SDK languages fall back to English.
[Tencent's language documentation](https://www.tencentcloud.com/document/product/1159/49680)
notes that slider challenges support its full language list, while other challenge
types support Chinese and English. This adapter provides complete CAPTCHA text
for `en` and `zh-CN`; additional page translations must be supplied separately.

Verify a built custom theme against the real provider in a disposable TLS fixture:

```sh
python3 tests/integration/run_registration.py --theme-jar /path/to/invoker-shadcn.jar
```
