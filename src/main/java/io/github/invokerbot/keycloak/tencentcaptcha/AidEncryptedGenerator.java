package io.github.invokerbot.keycloak.tencentcaptcha;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AidEncryptedGenerator {
    private static final int IV_LENGTH = 16;
    private static final int KEY_LENGTH = 32;
    private static final Duration MIN_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_TTL = Duration.ofSeconds(86_400);

    private AidEncryptedGenerator() {
    }

    static String generate(String appId, String appSecretKey, Instant now, Duration ttl, byte[] iv) {
        Objects.requireNonNull(appId, "app-id-missing");
        Objects.requireNonNull(appSecretKey, "app-secret-key-missing");
        Objects.requireNonNull(now, "now-missing");
        Objects.requireNonNull(ttl, "ttl-missing");
        Objects.requireNonNull(iv, "iv-missing");
        if (iv.length != IV_LENGTH) {
            throw new IllegalArgumentException("iv-length-invalid");
        }
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("ttl-out-of-range");
        }

        byte[] sourceKey = appSecretKey.getBytes(StandardCharsets.UTF_8);
        if (sourceKey.length == 0) {
            throw new IllegalArgumentException("app-secret-key-empty");
        }
        byte[] key = new byte[KEY_LENGTH];
        for (int index = 0; index < key.length; index++) {
            key[index] = sourceKey[index % sourceKey.length];
        }

        String plaintext = appId + "&" + now.getEpochSecond() + "&" + ttl.getSeconds();
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, encrypted, 0, iv.length);
            System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("aid-encryption-failed", exception);
        }
    }
}
