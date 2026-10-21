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
