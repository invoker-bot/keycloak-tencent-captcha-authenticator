<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=false; section>
    <#if section = "header">
        ${msg("captchaTitle")}
    <#elseif section = "form">
        <link rel="stylesheet" href="${url.resourcesPath}/css/tencent-captcha.css">
        <div class="tencent-captcha" data-captcha-challenge>
            <p class="tencent-captcha__prompt">${msg("captchaPrompt")}</p>
            <button class="tencent-captcha__button" id="tencent-captcha-action" type="button">
                ${msg("captchaAction")}
            </button>
            <p class="tencent-captcha__status" id="tencent-captcha-status" role="status" aria-live="polite"></p>
        </div>
        <script id="tencent-captcha-client"
                type="module"
                nonce="${cspNonce}"
                src="${url.resourcesPath}/js/tencent-captcha-client.js"
                data-app-id="${captchaAppId}"
                data-aid-encrypted="${aidEncrypted}"
                data-script-url="${captchaScriptUrl}"
                data-csp-nonce="${cspNonce}"
                data-login-action="${url.loginAction}"
                data-browser-error="${msg('captchaBrowserError')}"
                data-retry-label="${msg('captchaRetry')}"></script>
    </#if>
</@layout.registrationLayout>
