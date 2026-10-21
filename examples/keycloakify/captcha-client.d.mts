export const TENCENT_CAPTCHA_SCRIPT_URL: "https://turing.captcha.qcloud.com/TJCaptcha.js";

export type CaptchaProof = {
    ticket: string;
    randstr: string;
};

export type CaptchaDiagnostic = {
    category: "disaster-ticket";
    errorCode: number | null;
};

type TencentCaptchaResult = Partial<CaptchaProof> & {
    ret?: number;
    errorCode?: unknown;
};

type TencentCaptchaConstructor = new (
    appId: string,
    callback: (result: TencentCaptchaResult) => void,
    options: { aidEncrypted: string; userLanguage: string }
) => { show(): void };

export type CaptchaWindow = Window & {
    TencentCaptcha?: TencentCaptchaConstructor;
};

export type CaptchaConfig = {
    appId: string;
    aidEncrypted: string;
    languageTag: string;
    scriptUrl: typeof TENCENT_CAPTCHA_SCRIPT_URL;
    cspNonce: string;
    document: Document;
    window: CaptchaWindow;
    reportError?: (diagnostic: CaptchaDiagnostic) => void;
};

export type SingleFlightCaptchaController = {
    run(): Promise<void>;
};

export function loadTencentCaptcha(
    scriptUrl: string,
    cspNonce: string,
    document: Document,
    window: CaptchaWindow
): Promise<TencentCaptchaConstructor>;

export function solveCaptcha(config: CaptchaConfig): Promise<CaptchaProof>;

export function submitProof(
    loginAction: string,
    proof: CaptchaProof,
    document: Document
): void;

export function createSingleFlightCaptchaController(
    config: CaptchaConfig & { loginAction: string }
): SingleFlightCaptchaController;
