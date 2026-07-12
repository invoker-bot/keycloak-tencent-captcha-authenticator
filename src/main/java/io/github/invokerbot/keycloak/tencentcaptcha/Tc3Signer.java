package io.github.invokerbot.keycloak.tencentcaptcha;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Tc3Signer {
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SIGNED_HEADERS = "content-type;host";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private Tc3Signer() {
    }

    static String authorization(String secretId, String secretKey, String service, String host, String payload,
            Instant now) {
        Objects.requireNonNull(secretId, "secret-id-missing");
        Objects.requireNonNull(secretKey, "secret-key-missing");
        Objects.requireNonNull(service, "service-missing");
        Objects.requireNonNull(host, "host-missing");
        Objects.requireNonNull(payload, "payload-missing");
        Objects.requireNonNull(now, "now-missing");

        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n" + "host:" + host + "\n";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + SIGNED_HEADERS + "\n" + sha256Hex(payload);

        String date = DATE.format(now);
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + now.getEpochSecond() + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));

        return ALGORITHM + " Credential=" + secretId + "/" + credentialScope + ", SignedHeaders=" + SIGNED_HEADERS
                + ", Signature=" + signature;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("sha256-unavailable", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("hmac-sha256-unavailable", exception);
        }
    }
}
