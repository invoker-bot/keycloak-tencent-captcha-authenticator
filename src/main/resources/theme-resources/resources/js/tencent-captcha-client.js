export const TENCENT_CAPTCHA_SCRIPT_URL = "https://turing.captcha.qcloud.com/TJCaptcha.js";

let pendingScript;

// Tencent's language codes differ from Keycloak's BCP 47 tags. Never leave the
// choice to the browser: its language may differ from the page's selection.
const TENCENT_LANGUAGES = new Set((
    "zh-cn zh-hk zh-tw en en-in ar cs-cz de es es-la fr fil he hi id it ja ko " +
    "lo ms my nl pl pt pt-pt ro-ro ru th tr uk-ua ur vi"
).split(" "));

export function tencentCaptchaLanguage(languageTag) {
    const tag = typeof languageTag === "string" ? languageTag.trim().replaceAll("_", "-").toLowerCase() : "";
    if (TENCENT_LANGUAGES.has(tag)) return tag;
    const language = tag.split("-")[0];
    if (language === "zh") {
        if (tag.includes("-hk")) return "zh-hk";
        return tag.includes("-hant") || tag.includes("-tw") ? "zh-tw" : "zh-cn";
    }
    const regional = {cs: "cs-cz", ro: "ro-ro", uk: "uk-ua"}[language];
    return regional ?? (TENCENT_LANGUAGES.has(language) ? language : "en");
}

function captchaError(code) {
    return new Error(code);
}

export function loadTencentCaptcha(scriptUrl, cspNonce, document, window) {
    if (scriptUrl !== TENCENT_CAPTCHA_SCRIPT_URL) {
        return Promise.reject(captchaError("captcha-script-url-not-allowed"));
    }
    if (typeof window.TencentCaptcha === "function") {
        return Promise.resolve(window.TencentCaptcha);
    }
    if (pendingScript?.document === document && pendingScript.window === window) {
        return pendingScript.promise;
    }

    const script = document.createElement("script");
    script.src = TENCENT_CAPTCHA_SCRIPT_URL;
    script.async = true;
    script.nonce = cspNonce;

    const promise = new Promise((resolve, reject) => {
        const fail = () => {
            if (pendingScript?.promise === promise) {
                pendingScript = undefined;
            }
            script.remove();
            reject(captchaError("captcha-script-load-failed"));
        };
        script.addEventListener("load", () => {
            if (typeof window.TencentCaptcha !== "function") {
                fail();
                return;
            }
            resolve(window.TencentCaptcha);
        });
        script.addEventListener("error", fail);
    });

    pendingScript = {document, promise, window};
    document.head.append(script);
    return promise;
}

export async function solveCaptcha({appId, aidEncrypted, languageTag, scriptUrl, cspNonce, document, window, reportError}) {
    const TencentCaptcha = await loadTencentCaptcha(scriptUrl, cspNonce, document, window);

    return new Promise((resolve, reject) => {
        let settled = false;
        const finish = result => {
            if (settled) return;
            settled = true;
            if (result?.ret !== 0) {
                reject(captchaError("captcha-not-completed"));
                return;
            }
            const ticket = typeof result.ticket === "string" ? result.ticket.trim() : "";
            const randstr = typeof result.randstr === "string" ? result.randstr.trim() : "";
            if (ticket.startsWith("trerror_")) {
                if (typeof reportError === "function") {
                    const errorCode = Number.isInteger(result.errorCode) ? result.errorCode : null;
                    try {
                        reportError({category: "disaster-ticket", errorCode});
                    } catch {
                        // Diagnostics must not alter fail-closed CAPTCHA handling.
                    }
                }
                reject(captchaError("captcha-invalid-proof"));
                return;
            }
            if (ticket === "" || randstr === "" || (result.errorCode != null && result.errorCode !== 0)) {
                reject(captchaError("captcha-invalid-proof"));
                return;
            }
            resolve({ticket, randstr});
        };

        try {
            const userLanguage = tencentCaptchaLanguage(languageTag);
            const captcha = new TencentCaptcha(appId, finish, {aidEncrypted, userLanguage});
            captcha.show();
        } catch {
            reject(captchaError("captcha-invocation-failed"));
        }
    });
}

export function submitProof(loginAction, proof, document) {
    const form = document.createElement("form");
    form.method = "POST";
    form.action = loginAction;

    for (const name of ["ticket", "randstr"]) {
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = name;
        input.value = proof[name];
        form.append(input);
    }

    document.body.append(form);
    form.submit();
}

export function createSingleFlightCaptchaController(config) {
    let inFlight;
    let submitted = false;

    return {
        run() {
            if (inFlight !== undefined) return inFlight;
            if (submitted) return Promise.resolve();

            const attempt = (async () => {
                const proof = await solveCaptcha(config);
                submitProof(config.loginAction, proof, config.document);
                submitted = true;
            })();
            inFlight = attempt;
            attempt.catch(() => {
                if (inFlight === attempt) inFlight = undefined;
            });
            return attempt;
        }
    };
}

export function bootstrapTencentCaptcha(document, window) {
    const root = document.getElementById("tencent-captcha-client");
    const button = document.getElementById("tencent-captcha-action");
    const status = document.getElementById("tencent-captcha-status");
    if (root === null || button === null || status === null) {
        throw captchaError("captcha-bootstrap-element-missing");
    }

    const controller = createSingleFlightCaptchaController({
        appId: root.dataset.appId,
        aidEncrypted: root.dataset.aidEncrypted,
        languageTag: root.dataset.languageTag,
        scriptUrl: root.dataset.scriptUrl,
        cspNonce: root.dataset.cspNonce,
        loginAction: root.dataset.loginAction,
        document,
        window,
        reportError: typeof window.console?.warn === "function"
            ? diagnostic => window.console.warn(
                "tencent-captcha-diagnostic",
                JSON.stringify(diagnostic)
            )
            : undefined
    });

    const run = async () => {
        button.disabled = true;
        button.textContent = root.dataset.verifyingLabel ?? button.textContent;
        status.textContent = "";
        try {
            await controller.run();
        } catch {
            status.textContent = root.dataset.browserError;
            button.textContent = root.dataset.retryLabel;
            button.disabled = false;
        }
    };
    button.addEventListener("click", run);
    // The server disables automatic activation on rejection pages, preventing
    // automatic POST/rechallenge loops. Cancellation/network failures need a click.
    if (root.dataset.autoStart === "true") void run();
    return controller;
}

if (typeof document !== "undefined" && typeof window !== "undefined") {
    bootstrapTencentCaptcha(document, window);
}
