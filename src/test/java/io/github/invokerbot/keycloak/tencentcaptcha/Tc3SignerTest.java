package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class Tc3SignerTest {
    private static final Instant FIXED_NOW = Instant.parse("2026-07-12T00:00:00Z");
    private static final String PAYLOAD = "{\"AppSecretKey\":\"CAPTCHASECRETEXAMPLE\",\"CaptchaAppId\":123456789,"
            + "\"CaptchaType\":9,\"Randstr\":\"rand\",\"Ticket\":\"ticket\"," + "\"UserIp\":\"203.0.113.9\"}";

    @Test
    void matchesFullTc3AuthorizationVector() {
        assertEquals(
                "TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2026-07-12/captcha/tc3_request, "
                        + "SignedHeaders=content-type;host, "
                        + "Signature=b56d0e8def3e4fc0be6dfa1bce561ed1b606b24296957e8aa5d1ddc9d94ddd4b",
                Tc3Signer.authorization("AKIDEXAMPLE", "SECRETKEYEXAMPLE", "captcha", "captcha.tencentcloudapi.com",
                        PAYLOAD, FIXED_NOW));
    }

    @Test
    void payloadChangesSignature() {
        String original = Tc3Signer.authorization("AKIDEXAMPLE", "SECRETKEYEXAMPLE", "captcha",
                "captcha.tencentcloudapi.com", PAYLOAD, FIXED_NOW);
        String changed = Tc3Signer.authorization("AKIDEXAMPLE", "SECRETKEYEXAMPLE", "captcha",
                "captcha.tencentcloudapi.com", PAYLOAD + " ", FIXED_NOW);

        assertNotEquals(original, changed);
    }
}
