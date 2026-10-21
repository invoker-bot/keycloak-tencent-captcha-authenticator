import { readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { patchCaptchaTranslations } from "./captcha-translations.mjs";

const assets = dirname(fileURLToPath(import.meta.url));
if (!process.argv[2]) throw new Error("Usage: node patch-invisible.mjs THEME_SOURCE [PROVIDER_CLIENT_JS]");
const root = resolve(process.argv[2]);
const paths = {
    page: "src/login/pages/tencent-captcha/Page.tsx",
    context: "src/login/KcContext.ts",
    mock: "src/login/mocks/getKcContextMock.ts",
    i18n: "src/login/i18n.ts"
};
const files = Object.fromEntries(Object.entries(paths).map(([key, path]) =>
    [key, readFileSync(resolve(root, path), "utf8").replaceAll("\r\n", "\n")]));
function once(text, before, after) {
    if (text.split(before).length !== 2) throw new Error("Incompatible or already patched theme: " + before);
    return text.replace(before, after);
}
files.page = once(files.page, 'import { useRef, useState } from "react";',
    'import { useCallback, useEffect, useRef, useState } from "react";');
files.page = once(files.page, "useState(false)", "useState(kcContext.captchaAutoStart === true)");
files.page = once(files.page, "    if (controller.current === null) {",
    "    const autoStarted = useRef(false);\n\n    if (controller.current === null) {");
files.page = once(files.page, "    async function handleContinue() {",
    "    const handleContinue = useCallback(async () => {");
files.page = once(files.page, "    }\n\n    return (", `    }, [msg]);

    useEffect(() => {
        if (kcContext.captchaAutoStart !== true || autoStarted.current) return;
        autoStarted.current = true;
        void handleContinue();
    }, [kcContext.captchaAutoStart, handleContinue]);

    return (`);
files.page = once(files.page, '{msg("captchaDescription")}',
    '{msg(kcContext.captchaAutoStart ? "captchaAutomaticPrompt" : "captchaDescription")}');
files.page = once(files.page, '{msg("captchaContinue")}',
    '{msg(busy ? "captchaChecking" : "captchaContinue")}');
files.context = once(files.context, "        captchaAppId: string;",
    "        captchaAppId: string;\n        captchaAutoStart: boolean;");
files.mock = once(files.mock, '        captchaAppId: "123456789",',
    '        captchaAppId: "123456789",\n        captchaAutoStart: false,');
files.i18n = patchCaptchaTranslations(files.i18n);
const client = readFileSync(resolve(process.argv[3] ?? resolve(assets,
    "../../src/main/resources/theme-resources/resources/js/tencent-captcha-client.js")), "utf8");
const split = "export function bootstrapTencentCaptcha(";
if (client.split(split).length !== 2) throw new Error("Unexpected provider client structure");
// Validate all patches before writing; React owns activation and retry state.
for (const [key, text] of Object.entries(files)) writeFileSync(resolve(root, paths[key]), text);
const pageDir = dirname(resolve(root, paths.page));
writeFileSync(resolve(pageDir, "captcha-client.mjs"), client.split(split)[0]);
copyFileSync(resolve(assets, "captcha-client.d.mts"), resolve(pageDir, "captcha-client.d.mts"));
console.log("Patched automatic CAPTCHA activation with manual retry after rejection");
