export const captchaTranslations = {
    en: {
        captchaTitle: "Security verification",
        captchaDescription: "Complete the CAPTCHA challenge to continue.",
        captchaContinue: "Start verification",
        captchaRetry: "The verification challenge could not be completed. Please try again.",
        captchaVerificationFailed: "Verification was not accepted. Please try again.",
        captchaConfigUnavailable: "Security verification is temporarily unavailable. Please try again later."
    },
    "zh-CN": {
        captchaTitle: "安全验证",
        captchaDescription: "请完成人机验证后继续。",
        captchaContinue: "开始验证",
        captchaRetry: "未能完成人机验证，请重试。",
        captchaVerificationFailed: "验证未通过，请重试。",
        captchaConfigUnavailable: "安全验证暂时不可用，请稍后重试。"
    }
};

// Keycloakify statically extracts these strings for server-rendered errors.
// Emit literals instead of a runtime merge, which its extractor cannot evaluate.
export function patchCaptchaTranslations(source) {
    let text = source.replaceAll("\r\n", "\n");
    for (const [locale, messages] of Object.entries(captchaTranslations)) {
        const marker = `        ${locale === "en" ? "en" : '"zh-CN"'}: {\n`;
        if (text.split(marker).length !== 2) throw new Error("Missing unique locale: " + locale);
        const start = text.indexOf(marker) + marker.length;
        const end = text.indexOf("        }", start);
        if (end < 0) throw new Error("Unterminated locale: " + locale);
        let block = text.slice(start, end);
        for (const key of Object.keys(messages)) {
            block = block.replace(new RegExp(`^            ${key}: [^\\n]+\\n`, "gm"), "");
        }
        block = block.trimEnd().replace(/,$/, "");
        const added = Object.entries(messages).map(([key, value]) => `            ${key}: ${JSON.stringify(value)}`).join(",\n");
        text = text.slice(0, start) + block + (block ? ",\n" : "") + added + "\n" + text.slice(end);
    }
    return text;
}
