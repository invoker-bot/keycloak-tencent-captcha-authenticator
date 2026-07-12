package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AidEncryptedGeneratorTest {
    private static final Instant FIXED_NOW = Instant.parse("2026-07-12T00:00:00Z");
    private static final byte[] FIXED_IV = IntStream.range(0, 16).collect(() -> new byte[16],
            (bytes, value) -> bytes[value] = (byte) value, (left, right) -> {
            });

    @Test
    void matchesFixedAesCbcVector() {
        assertEquals("AAECAwQFBgcICQoLDA0OD41wf9vBqmJKPfy1LbewiUI96Lm2EKww57Pt4a0dOOIu", AidEncryptedGenerator
                .generate("123456789", "test-secret", FIXED_NOW, Duration.ofSeconds(300), FIXED_IV));
    }

    @Test
    void rejectsEmptyKeyInvalidIvAndInvalidTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> AidEncryptedGenerator.generate("123456789", "", FIXED_NOW, Duration.ofSeconds(300), FIXED_IV));
        assertThrows(IllegalArgumentException.class, () -> AidEncryptedGenerator.generate("123456789", "test-secret",
                FIXED_NOW, Duration.ofSeconds(300), new byte[15]));
        assertThrows(IllegalArgumentException.class,
                () -> AidEncryptedGenerator.generate("123456789", "test-secret", FIXED_NOW, Duration.ZERO, FIXED_IV));
        assertThrows(IllegalArgumentException.class, () -> AidEncryptedGenerator.generate("123456789", "test-secret",
                FIXED_NOW, Duration.ofSeconds(86_401), FIXED_IV));
        assertThrows(IllegalArgumentException.class, () -> AidEncryptedGenerator.generate("123456789", "test-secret",
                FIXED_NOW, Duration.ofSeconds(86_400, 1), FIXED_IV));
    }
}
