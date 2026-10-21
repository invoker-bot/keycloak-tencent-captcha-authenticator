import assert from "node:assert/strict";
import test from "node:test";
import { captchaTranslations, patchCaptchaTranslations } from "../../../examples/keycloakify/captcha-translations.mjs";

test("English and Simplified Chinese translate the same CAPTCHA messages", () => {
    assert.deepEqual(Object.keys(captchaTranslations.en).sort(), Object.keys(captchaTranslations["zh-CN"]).sort());
    assert.equal(captchaTranslations.en.captchaTitle, "Security verification");
    assert.equal(captchaTranslations["zh-CN"].captchaTitle, "安全验证");
    for (const [key, value] of Object.entries(captchaTranslations.en)) {
        assert.equal(/[\u3400-\u9fff]/u.test(value), false, `English ${key} contains Chinese`);
        assert.notEqual(value, captchaTranslations["zh-CN"][key]);
    }
});

test("repairs misplaced Chinese CAPTCHA strings without changing login translations", () => {
    const before = `{
        en: {
            doLogIn: "Sign in",
            captchaTitle: "安全验证"
        },
        "zh-CN": {
            doLogIn: "登录"
        },
        de: {doLogIn: "Anmelden"}
    }`;
    const after = patchCaptchaTranslations(before.replaceAll("\n", "\r\n"));
    // The result must remain a plain literal that Keycloakify can extract.
    const parsed = Function(`return (${after});`)();
    assert.equal(parsed.en.captchaTitle, "Security verification");
    assert.equal(parsed["zh-CN"].captchaTitle, "安全验证");
    assert.equal(parsed.en.doLogIn, "Sign in");
    assert.equal(parsed["zh-CN"].doLogIn, "登录");
    assert.equal(parsed.de.doLogIn, "Anmelden");
    assert.equal(patchCaptchaTranslations(after), after);
});
