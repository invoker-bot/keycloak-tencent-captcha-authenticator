import { readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { patchCaptchaTranslations } from "./captcha-translations.mjs";

const assets = dirname(fileURLToPath(import.meta.url));
const root = resolve(process.argv[2] ?? "");
if (!process.argv[2]) throw new Error("Usage: node patch-multilingual.mjs THEME_SOURCE [PROVIDER_CLIENT_JS]");
const i18nPath = resolve(root, "src/login/i18n.ts");
const pagePath = resolve(root, "src/login/pages/tencent-captcha/Page.tsx");
let i18n = readFileSync(i18nPath, "utf8").replaceAll("\r\n", "\n");
let page = readFileSync(pagePath, "utf8").replaceAll("\r\n", "\n");
const client = readFileSync(resolve(process.argv[3] ?? resolve(assets,
    "../../src/main/resources/theme-resources/resources/js/tencent-captcha-client.js")), "utf8");
function once(text, before, after) {
    if (text.split(before).length !== 2) throw new Error("Incompatible or already patched theme: " + before);
    return text.replace(before, after);
}
i18n = patchCaptchaTranslations(i18n);
page = once(page, "const { msg } = useI18n();", "const { msg, currentLanguage } = useI18n();");
page = once(page, "aidEncrypted: kcContext.aidEncrypted,",
    "aidEncrypted: kcContext.aidEncrypted,\n            languageTag: currentLanguage.languageTag,");
// Reuse the tested provider client; its DOM bootstrap belongs only to the FTL
// page. React owns the custom page's button and creates its own controller.
const split = "export function bootstrapTencentCaptcha(";
if (client.split(split).length !== 2) throw new Error("Unexpected provider client structure");
writeFileSync(i18nPath, i18n);
writeFileSync(pagePath, page);
copyFileSync(resolve(assets, "captcha-client.d.mts"), resolve(dirname(pagePath), "captcha-client.d.mts"));
writeFileSync(resolve(dirname(pagePath), "captcha-client.mjs"), client.split(split)[0]);
console.log("Patched CAPTCHA translations and selected-language propagation");
