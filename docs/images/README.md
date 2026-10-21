# Screenshot provenance

`captcha-en.jpg` and `captcha-zh-CN.jpg` are unedited, 1280 × 720 browser
captures taken on 2026-10-22 from a disposable local Keycloak 26.7.0 instance.
The instance runs a v0.1.2 provider build with a `REQUIRED`
`tencent-captcha` Browser Flow execution and the display name `CAPTCHA Demo`.
Only synthetic integration-test CAPTCHA configuration was used. The screenshots
contain no production realm name, account information, credentials, or session URL.

The appearance comes from a customized `invoker-shadcn` login theme based on
[Keycloakify shadcn starter v250004.0.24](https://github.com/Oussemasahbeni/keycloakify-shadcn-starter/tree/v250004.0.24)
(upstream commit `57dc7f0181ef3af115c8064ac8eee854724252b3`). Its defaults are
`centered-card`, `blue`, `neutral`, `medium`, and `geist`. The custom
`tencent-captcha.ftl` page preserves the provider's action and CAPTCHA attributes.
The theme JAR is an optional deployment integration and is not shipped in this
repository or in its releases. Installing the upstream starter alone does not add
this custom CAPTCHA page.

Realm internationalization is enabled with the `en` and `zh-CN` locales. The
theme includes the repository's [multilingual adapter](../../examples/keycloakify/README.md#language-selection).
Both images show the same initial CAPTCHA step, before the Tencent challenge
opens. The language selector and mode button were used to switch from English
in light mode to Simplified Chinese in dark mode between the two captures.
These screenshots demonstrate appearance, not a completed Tencent challenge or
registration protection. The provider's bundled FreeMarker page has a different
appearance determined by the active Keycloak login theme.

To refresh the images, use a disposable Keycloak instance, the released provider,
synthetic test configuration, and the same custom theme. Open the protected
account/login route without submitting credentials, select English and light mode,
capture at 1280 × 720, switch to Simplified Chinese and dark mode, and capture again.
Check both images for private information before
committing them. Keep deployment configuration and theme binaries out of this folder.
