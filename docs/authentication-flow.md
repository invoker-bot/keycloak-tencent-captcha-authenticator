# Authentication flow

The provider ID is `tencent-captcha`. It supports only the `REQUIRED` requirement and requires no per-user enrollment or Authenticator Config.

## Recommended placement

Create a realm-owned copy of the built-in Browser Flow and add Tencent CAPTCHA inside its Forms subflow immediately before Username Password Form:

```text
Copied Browser Flow
├── Cookie (ALTERNATIVE)
├── Identity Provider Redirector (ALTERNATIVE)
└── Forms (ALTERNATIVE)
    ├── Tencent CAPTCHA [tencent-captcha] (REQUIRED)
    └── Username Password Form (REQUIRED)
```

Admin Console procedure:

1. Select the target realm and open **Authentication > Flows**.
2. Find **Browser**, choose **Duplicate**, and give the copy a realm-specific name.
3. Expand **Forms** in the copy.
4. Choose **Add execution**, select **Tencent CAPTCHA**, and add it.
5. Set its requirement to `REQUIRED`.
6. Move it to immediately before **Username Password Form**.
7. Bind the copy under **Authentication > Bindings > Browser Flow**.
8. In a private browser session, test failure and success with a non-administrator account before broad rollout. Keep an administrative recovery session or tested CLI access while changing flow bindings.

Do not edit the built-in Browser flow in place. A copy gives the realm an explicit rollback target and avoids upgrade surprises.

## What the placement protects

With the recommended order, a fresh interactive username/password login reaches CAPTCHA before credentials are accepted. The execution creates a new 300-second `aidEncrypted` value and a new request CSP nonce for every challenge. The page posts `ticket` and `randstr` only to Keycloak's exact `url.loginAction`; the server verifies the proof before continuing.

The provider succeeds only when Tencent's `DescribeCaptchaResult` returns an integral `CaptchaCode == 1`. Every invalid, unavailable, or ambiguous outcome is fail-closed.

## Intentional boundaries

The copied Browser Flow normally evaluates SSO Cookie before Forms. A valid SSO Cookie therefore bypasses a new CAPTCHA. Put differently, this execution protects fresh Browser Flow credential entry, not every SSO navigation. Removing the cookie bypass would change SSO behavior and requires a separate threat-model decision.

The following paths are not automatically covered:

- registration and registration form actions;
- reset credentials / password reset;
- required actions and action-token links;
- identity-provider-first or broker-specific flows that complete before Forms;
- direct grant (`grant_type=password`), which has no browser challenge page;
- service accounts, token exchange, device authorization, and account-console sessions already authenticated by SSO Cookie.

If one of these entry points needs anti-automation controls, configure a suitable independent flow or control and test it. Do not assume binding this Browser Flow changes registration, reset, or direct grant.

## Template override contract

The built-in `tencent-captcha.ftl` works with Keycloak's active login theme and is the supported default. A realm login theme may override the same template while preserving:

- attributes `captchaAppId`, `aidEncrypted`, `captchaScriptUrl`, `cspNonce`, and standard `url.loginAction`;
- exact POST field names `ticket` and `randstr`;
- the request-scoped nonce on the local module script;
- the exact Tencent script URL supplied by `captchaScriptUrl`;
- accessible button/status behavior and generic localized error messages.

Do not expose `CAPTCHA_APP_SECRET_KEY`, `TENCENT_SECRET_ID`, or `TENCENT_SECRET_KEY` to the template. Do not replace the form action with an application URL or accept a caller-controlled action target.

## Rollout and rollback

Before binding, record the previous Browser Flow name. Roll out to a test realm or maintenance window, validate a fresh login and an SSO Cookie login, then bind additional realms.

To roll back flow configuration, bind the previous Browser Flow first. Only after no bound flow references `tencent-captcha` should the provider JAR be removed and Keycloak rebuilt. See [README lifecycle steps](../README.md#upgrade-rollback-and-uninstall).
