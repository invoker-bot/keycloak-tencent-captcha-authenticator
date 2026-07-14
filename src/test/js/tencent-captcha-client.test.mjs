import assert from "node:assert/strict";
import test from "node:test";

import {
    TENCENT_CAPTCHA_SCRIPT_URL,
    bootstrapTencentCaptcha,
    createSingleFlightCaptchaController,
    loadTencentCaptcha,
    solveCaptcha,
    submitProof
} from "../../main/resources/theme-resources/resources/js/tencent-captcha-client.js";

const EXPECTED_URL = "https://turing.captcha.qcloud.com/TJCaptcha.js";

function scriptDocument() {
    const appended = [];
    const scripts = [];
    return {
        appended,
        scripts,
        head: {
            append(node) {
                appended.push(node);
            }
        },
        createElement(tagName) {
            assert.equal(tagName, "script");
            const listeners = new Map();
            const script = {
                async: false,
                nonce: "",
                src: "",
                removed: false,
                addEventListener(name, listener) {
                    listeners.set(name, listener);
                },
                dispatch(name) {
                    listeners.get(name)?.();
                },
                remove() {
                    this.removed = true;
                }
            };
            scripts.push(script);
            return script;
        }
    };
}

function formDocument() {
    const forms = [];
    return {
        forms,
        body: {
            append(form) {
                forms.push(form);
            }
        },
        createElement(tagName) {
            if (tagName === "form") {
                return {
                    children: [],
                    method: "",
                    action: "",
                    submitted: false,
                    append(input) {
                        this.children.push(input);
                    },
                    submit() {
                        this.submitted = true;
                    }
                };
            }
            assert.equal(tagName, "input");
            return { type: "", name: "", value: "" };
        }
    };
}

function bootstrapFixture() {
    const scripts = scriptDocument();
    const forms = formDocument();
    const document = {
        scripts: scripts.scripts,
        head: scripts.head,
        forms: forms.forms,
        body: forms.body,
        createElement: tagName => tagName === "script"
            ? scripts.createElement(tagName)
            : forms.createElement(tagName)
    };
    const listeners = new Map();
    const root = {
        dataset: {
            appId: "123456789",
            aidEncrypted: "encrypted",
            scriptUrl: EXPECTED_URL,
            cspNonce: "nonce-from-data",
            loginAction: "https://id.example.test/login-action?code=opaque&execution=exact",
            browserError: "Browser error",
            retryLabel: "Retry"
        }
    };
    const button = {
        disabled: false,
        textContent: "Verify",
        addEventListener(name, listener) {
            listeners.set(name, listener);
        }
    };
    const status = { textContent: "" };
    document.getElementById = id => ({
        "tencent-captcha-client": root,
        "tencent-captcha-action": button,
        "tencent-captcha-status": status
    })[id] ?? null;
    return {button, document, forms, listeners, scripts, status};
}

test("exports and enforces the exact Tencent script URL with a nonce", async () => {
    assert.equal(TENCENT_CAPTCHA_SCRIPT_URL, EXPECTED_URL);
    const document = scriptDocument();
    const window = {};

    await assert.rejects(
        loadTencentCaptcha("https://example.invalid/captcha.js", "nonce-1", document, window),
        /captcha-script-url-not-allowed/
    );
    assert.equal(document.scripts.length, 0);

    const loading = loadTencentCaptcha(EXPECTED_URL, "nonce-1", document, window);
    assert.equal(document.scripts.length, 1);
    assert.equal(document.scripts[0].src, EXPECTED_URL);
    assert.equal(document.scripts[0].nonce, "nonce-1");
    assert.equal(document.scripts[0].async, true);
    window.TencentCaptcha = function TencentCaptcha() {};
    document.scripts[0].dispatch("load");
    assert.equal(await loading, window.TencentCaptcha);
});

test("deduplicates concurrent script loading and permits retry after an error", async () => {
    const document = scriptDocument();
    const window = {};

    const first = loadTencentCaptcha(EXPECTED_URL, "nonce", document, window);
    const duplicate = loadTencentCaptcha(EXPECTED_URL, "nonce", document, window);
    assert.equal(first, duplicate);
    document.scripts[0].dispatch("error");
    await assert.rejects(first, /captcha-script-load-failed/);
    assert.equal(document.scripts[0].removed, true);

    const retry = loadTencentCaptcha(EXPECTED_URL, "nonce", document, window);
    assert.equal(document.scripts.length, 2);
    window.TencentCaptcha = function TencentCaptcha() {};
    document.scripts[1].dispatch("load");
    await retry;
});

test("constructs TencentCaptcha with exactly aidEncrypted and returns trimmed proof", async () => {
    let captured;
    const window = {
        TencentCaptcha: function TencentCaptcha(appId, callback, options) {
            captured = { appId, options };
            this.show = () => callback({ ret: 0, ticket: " ticket ", randstr: " rand " });
        }
    };

    const proof = await solveCaptcha({
        appId: "123456789",
        aidEncrypted: "encrypted",
        scriptUrl: EXPECTED_URL,
        cspNonce: "nonce",
        document: scriptDocument(),
        window
    });

    assert.deepEqual(captured, { appId: "123456789", options: { aidEncrypted: "encrypted" } });
    assert.deepEqual(proof, { ticket: "ticket", randstr: "rand" });
});

for (const [name, result, error] of [
    ["cancelled challenge", { ret: 2 }, "captcha-not-completed"],
    ["empty callback", undefined, "captcha-not-completed"],
    ["empty proof", { ret: 0, ticket: " ", randstr: "rand" }, "captcha-invalid-proof"]
]) {
    test(`does not accept ${name}`, async () => {
        const window = {
            TencentCaptcha: function TencentCaptcha(appId, callback) {
                this.show = () => callback(result);
            }
        };
        await assert.rejects(
            solveCaptcha({
                appId: "123",
                aidEncrypted: "encrypted",
                scriptUrl: EXPECTED_URL,
                cspNonce: "nonce",
                document: scriptDocument(),
                window
            }),
            new RegExp(error)
        );
    });
}

test("does not accept disaster proof and reports only a safe numeric diagnostic", async () => {
    const diagnostics = [];
    const window = {
        TencentCaptcha: function TencentCaptcha(appId, callback) {
            this.show = () => callback({
                ret: 0,
                ticket: "trerror_sensitive-ticket",
                randstr: "sensitive-randstr",
                errorCode: 1003,
                errorMessage: "sensitive-provider-message"
            });
        }
    };

    await assert.rejects(
        solveCaptcha({
            appId: "123",
            aidEncrypted: "encrypted",
            scriptUrl: EXPECTED_URL,
            cspNonce: "nonce",
            document: scriptDocument(),
            window,
            reportError: diagnostic => diagnostics.push(diagnostic)
        }),
        /captcha-invalid-proof/
    );
    assert.deepEqual(diagnostics, [{category: "disaster-ticket", errorCode: 1003}]);
});

for (const [name, result] of [
    ["missing errorCode", {ret: 0, ticket: "trerror_missing", randstr: "randstr"}],
    ["non-integral errorCode", {ret: 0, ticket: "trerror_fractional", randstr: "randstr", errorCode: 1003.5}]
]) {
    test(`reports null for disaster proof with ${name}`, async () => {
        const diagnostics = [];
        const window = {
            TencentCaptcha: function TencentCaptcha(appId, callback) {
                this.show = () => callback(result);
            }
        };

        await assert.rejects(
            solveCaptcha({
                appId: "123",
                aidEncrypted: "encrypted",
                scriptUrl: EXPECTED_URL,
                cspNonce: "nonce",
                document: scriptDocument(),
                window,
                reportError: diagnostic => diagnostics.push(diagnostic)
            }),
            /captcha-invalid-proof/
        );
        assert.deepEqual(diagnostics, [{category: "disaster-ticket", errorCode: null}]);
    });
}

test("rejects disaster proof even when the diagnostic sink throws", async () => {
    const window = {
        TencentCaptcha: function TencentCaptcha(appId, callback) {
            this.show = () => callback({ret: 0, ticket: "trerror_1003", randstr: "randstr", errorCode: 1003});
        }
    };

    await assert.rejects(
        solveCaptcha({
            appId: "123",
            aidEncrypted: "encrypted",
            scriptUrl: EXPECTED_URL,
            cspNonce: "nonce",
            document: scriptDocument(),
            window,
            reportError() {
                throw new Error("diagnostic-sink-failed");
            }
        }),
        /captcha-invalid-proof/
    );
});

test("submits ticket and randstr only to the exact login action", () => {
    const document = formDocument();

    submitProof(
        "https://id.example.test/realms/demo/login-actions/authenticate?code=opaque",
        { ticket: "ticket", randstr: "rand", ignored: "secret" },
        document
    );

    assert.equal(document.forms.length, 1);
    const form = document.forms[0];
    assert.equal(form.method, "POST");
    assert.equal(form.action, "https://id.example.test/realms/demo/login-actions/authenticate?code=opaque");
    assert.equal(form.submitted, true);
    assert.deepEqual(
        form.children.map(({ name, type, value }) => ({ name, type, value })),
        [
            { name: "ticket", type: "hidden", value: "ticket" },
            { name: "randstr", type: "hidden", value: "rand" }
        ]
    );
});

test("single-flight controller prevents duplicate activation and submission", async () => {
    let callback;
    let shows = 0;
    const document = Object.assign(scriptDocument(), formDocument());
    const window = {
        TencentCaptcha: function TencentCaptcha(appId, next) {
            callback = next;
            this.show = () => {
                shows += 1;
            };
        }
    };
    const controller = createSingleFlightCaptchaController({
        appId: "123",
        aidEncrypted: "encrypted",
        scriptUrl: EXPECTED_URL,
        cspNonce: "nonce",
        loginAction: "https://id.example.test/login-action",
        document,
        window
    });

    const first = controller.run();
    const duplicate = controller.run();
    assert.equal(first, duplicate);
    await Promise.resolve();
    assert.equal(shows, 1);
    callback({ ret: 0, ticket: "ticket", randstr: "rand" });
    await first;
    assert.equal(document.forms.length, 1);
    await controller.run();
    assert.equal(shows, 1);
    assert.equal(document.forms.length, 1);
});

test("bootstrap reads configuration from the exact external module element", async () => {
    const {document, listeners} = bootstrapFixture();

    let captured;
    const window = {};
    bootstrapTencentCaptcha(document, window);

    const activation = listeners.get("click")();
    assert.equal(document.scripts[0].src, EXPECTED_URL);
    assert.equal(document.scripts[0].nonce, "nonce-from-data");
    window.TencentCaptcha = function TencentCaptcha(appId, callback, options) {
        captured = {appId, options};
        this.show = () => callback({ret: 0, ticket: "ticket", randstr: "rand"});
    };
    document.scripts[0].dispatch("load");
    await activation;

    assert.deepEqual(captured, {appId: "123456789", options: {aidEncrypted: "encrypted"}});
    assert.equal(document.forms[0].action,
        "https://id.example.test/login-action?code=opaque&execution=exact");
    assert.deepEqual(document.forms[0].children.map(({name}) => name), ["ticket", "randstr"]);
});

test("bootstrap wires console.warn to receive only the safe disaster diagnostic", async () => {
    const {document, listeners, status} = bootstrapFixture();
    const diagnostics = [];
    const window = {
        console: {
            warn(...args) {
                diagnostics.push(args);
            }
        }
    };
    bootstrapTencentCaptcha(document, window);

    const activation = listeners.get("click")();
    window.TencentCaptcha = function TencentCaptcha(appId, callback) {
        this.show = () => callback({
            ret: 0,
            ticket: "trerror_sensitive-ticket",
            randstr: "sensitive-randstr",
            errorCode: 1003,
            errorMessage: "sensitive-provider-message"
        });
    };
    document.scripts[0].dispatch("load");
    await activation;

    assert.deepEqual(diagnostics, [[
        "tencent-captcha-diagnostic",
        '{"category":"disaster-ticket","errorCode":1003}'
    ]]);
    assert.equal(document.forms.length, 0);
    assert.equal(status.textContent, "Browser error");
});
