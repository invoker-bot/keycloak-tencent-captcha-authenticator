package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MimeTypeUtil;

class PackagedResourcesTest {
    private static final String[] RESOURCES = {"theme-resources/templates/tencent-captcha.ftl",
            "theme-resources/resources/js/tencent-captcha-client.js",
            "theme-resources/resources/css/tencent-captcha.css", "theme-resources/messages/messages_en.properties",
            "theme-resources/messages/messages_zh_CN.properties"};

    @Test
    void embedsEveryThemeResourceInTheProviderClasspath() throws IOException {
        for (String resource : RESOURCES) {
            assertJarResource(resource);
        }
    }

    @Test
    void serviceLoaderDescriptorNamesExactlyOneNeutralFactory() throws IOException {
        String descriptor = read("META-INF/services/org.keycloak.authentication.AuthenticatorFactory");

        assertEquals("io.github.invokerbot.keycloak.tencentcaptcha.TencentCaptchaAuthenticatorFactory\n", descriptor);
    }

    @Test
    void embedsProjectLicenseAndNoticeInJarMetadata() throws IOException {
        java.nio.file.Path project = java.nio.file.Path.of(System.getProperty("project.basedir"));
        assertEquals(java.nio.file.Files.readString(project.resolve("LICENSE")),
                java.nio.file.Files.readString(project.resolve("target/classes/META-INF/LICENSE")));
        String notice = java.nio.file.Files.readString(project.resolve("target/classes/META-INF/NOTICE"));
        assertEquals(java.nio.file.Files.readString(project.resolve("NOTICE")), notice);
        assertTrue(notice.contains("Apache Maven Wrapper 3.3.4"));
        assertTrue(notice.contains("Hans Dockter and Adam Murdoch"));
    }

    @Test
    void keycloak267ServesThePackagedBrowserModuleAsJavaScript() {
        assertEquals("text/javascript",
                MimeTypeUtil.getContentType("theme-resources/resources/js/tencent-captcha-client.js"));
        assertEquals("application/octet-stream",
                MimeTypeUtil.getContentType("theme-resources/resources/js/tencent-captcha-client.mjs"));
    }

    @Test
    void genericTemplateUsesNonceBearingExternalRootModuleWithDataConfiguration() throws IOException {
        String template = read("theme-resources/templates/tencent-captcha.ftl");

        assertTrue(template.contains("<#import \"template.ftl\" as layout>"));
        assertTrue(template.contains("${url.resourcesPath}/css/tencent-captcha.css"));
        assertTrue(Pattern.compile("(?s)<script(?=[^>]*id=\"tencent-captcha-client\")"
                + "(?=[^>]*type=\"module\")(?=[^>]*nonce=\"\\$\\{cspNonce}\")"
                + "(?=[^>]*src=\"\\$\\{url\\.resourcesPath}/js/tencent-captcha-client\\.js\")" + "[^>]*>\\s*</script>")
                .matcher(template).find());
        assertTrue(template.contains("data-app-id=\"${captchaAppId}\""));
        assertTrue(template.contains("data-aid-encrypted=\"${aidEncrypted}\""));
        assertTrue(template.contains("data-script-url=\"${captchaScriptUrl}\""));
        assertTrue(template.contains("data-csp-nonce=\"${cspNonce}\""));
        assertTrue(template.contains("data-login-action=\"${url.loginAction}\""));
        assertTrue(template.contains("data-browser-error=\"${msg('captchaBrowserError')}\""));
        assertTrue(template.contains("data-retry-label=\"${msg('captchaRetry')}\""));
        assertFalse(template.contains("tencent-captcha-client.mjs"));
        assertFalse(template.contains("import {"));
        assertFalse(template.contains("unsafe-inline"));
        assertFalse(template.contains("<form"));
    }

    @Test
    void bothMessageBundlesContainAllUserFacingStates() throws IOException {
        for (String resource : new String[]{"theme-resources/messages/messages_en.properties",
                "theme-resources/messages/messages_zh_CN.properties"}) {
            String messages = read(resource);
            assertTrue(messages.contains("captchaAction="));
            assertTrue(messages.contains("captchaRetry="));
            assertTrue(messages.contains("captchaConfigUnavailable="));
            assertTrue(messages.contains("captchaVerificationFailed="));
            assertTrue(messages.contains("captchaBrowserError="));
        }
    }

    private void assertJarResource(String name) throws IOException {
        assertFalse(read(name).isBlank(), name);
    }

    private String read(String name) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
