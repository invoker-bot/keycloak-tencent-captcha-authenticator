import { readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { patchCaptchaTranslations } from "./captcha-translations.mjs";

const assets = dirname(fileURLToPath(import.meta.url));
const root = resolve(process.argv[2] ?? "");
if (!process.argv[2]) throw new Error("Usage: node patch-multilingual.mjs THEME_SOURCE [PROVIDER_CLIENT_JS]");
const i18nPath = resolve(root, "src/login/i18n.ts");
const pagePath = resolve(root, "src/login/pages/tencent-captcha/Page.tsx");
const templatePath = resolve(root, "src/login/components/Template/Template.tsx");
const topBarPath = resolve(root, "src/login/components/Template/TemplateTopBar.tsx");
const togglePath = resolve(root, "src/login/components/ui/ThemeToggle.tsx");
let i18n = readFileSync(i18nPath, "utf8").replaceAll("\r\n", "\n");
let page = readFileSync(pagePath, "utf8").replaceAll("\r\n", "\n");
let template = readFileSync(templatePath, "utf8").replaceAll("\r\n", "\n");
let topBar = readFileSync(topBarPath, "utf8").replaceAll("\r\n", "\n");
let toggle = readFileSync(togglePath, "utf8").replaceAll("\r\n", "\n");
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
template = once(template, `    useEffect(() => {
        document.title =
            documentTitle ??
            msgStr("loginTitle", kcContext.realm.displayName || kcContext.realm.name);
    }, []);`, `    const resolvedDocumentTitle = documentTitle ??
        msgStr("loginTitle", kcContext.realm.displayName || kcContext.realm.name);
    useEffect(() => {
        document.title = resolvedDocumentTitle;
    }, [resolvedDocumentTitle]);`);
topBar = once(topBar, "const { enabledLanguages } = useI18n();", "const { enabledLanguages, msgStr } = useI18n();");
topBar = once(topBar, 'aria-label="Home"', 'aria-label={msgStr("themeHome")}');
toggle = 'import { useI18n } from "../../i18n";\n' + toggle;
toggle = once(toggle, "const { theme, setTheme } = useTheme();", "const { theme, setTheme } = useTheme();\n    const { msgStr } = useI18n();");
toggle = once(toggle, 'theme === "light" ? "Switch to dark mode" : "Switch to light mode"',
    'msgStr(theme === "light" ? "themeDarkMode" : "themeLightMode")');
// Reuse the tested provider client; its DOM bootstrap belongs only to the FTL
// page. React owns the custom page's button and creates its own controller.
const split = "export function bootstrapTencentCaptcha(";
if (client.split(split).length !== 2) throw new Error("Unexpected provider client structure");
writeFileSync(i18nPath, i18n);
writeFileSync(pagePath, page);
writeFileSync(templatePath, template);
writeFileSync(topBarPath, topBar);
writeFileSync(togglePath, toggle);
copyFileSync(resolve(assets, "captcha-client.d.mts"), resolve(dirname(pagePath), "captcha-client.d.mts"));
writeFileSync(resolve(dirname(pagePath), "captcha-client.mjs"), client.split(split)[0]);
console.log("Patched CAPTCHA translations and selected-language propagation");
