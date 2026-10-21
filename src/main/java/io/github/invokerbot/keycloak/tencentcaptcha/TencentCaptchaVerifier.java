package io.github.invokerbot.keycloak.tencentcaptcha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class TencentCaptchaVerifier {
    static final URI ENDPOINT = URI.create("https://captcha.tencentcloudapi.com");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int MAX_TICKET_LENGTH = 8_192;
    private static final int MAX_RANDSTR_LENGTH = 1_024;
    private static final int MAX_USER_IP_LENGTH = 255;
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SERVICE = "captcha";
    private static final String ACTION = "DescribeCaptchaResult";
    private static final String VERSION = "2019-07-22";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CaptchaSecrets secrets;
    private final HttpClient client;
    private final Clock clock;

    TencentCaptchaVerifier(CaptchaSecrets secrets, HttpClient client, Clock clock) {
        this.secrets = Objects.requireNonNull(secrets, "secrets-missing");
        this.client = Objects.requireNonNull(client, "http-client-missing");
        this.clock = Objects.requireNonNull(clock, "clock-missing");
    }

    static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    CaptchaVerificationResult verify(String ticket, String randstr, String userIp) {
        if (isBlank(ticket) || isBlank(randstr) || isBlank(userIp)) {
            return rejected("missing-proof", null);
        }
        if (ticket.length() > MAX_TICKET_LENGTH || randstr.length() > MAX_RANDSTR_LENGTH
                || userIp.length() > MAX_USER_IP_LENGTH) {
            return rejected("proof-too-large", null);
        }
        if (ticket.startsWith("trerror_")) {
            return rejected("disaster-ticket", null);
        }

        Instant now = clock.instant();
        String payload;
        try {
            payload = payload(ticket, randstr, userIp);
        } catch (JsonProcessingException | NumberFormatException exception) {
            return rejected("invalid-response", null);
        }

        String authorization = Tc3Signer.authorization(secrets.tencentSecretId(), secrets.tencentSecretKey(), SERVICE,
                ENDPOINT.getHost(), payload, now);
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).version(HttpClient.Version.HTTP_1_1)
                .timeout(REQUEST_TIMEOUT).header("Authorization", authorization).header("Content-Type", CONTENT_TYPE)
                .header("X-TC-Action", ACTION).header("X-TC-Version", VERSION)
                .header("X-TC-Timestamp", Long.toString(now.getEpochSecond()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return rejected("transport-error", null);
        } catch (IOException exception) {
            return rejected("transport-error", null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return rejected("transport-error", null);
        }

        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JsonProcessingException | RuntimeException exception) {
            return rejected("invalid-response", null);
        }
        JsonNode responseNode = root == null ? null : root.get("Response");
        if (responseNode == null || !responseNode.isObject()) {
            return rejected("invalid-response", null);
        }
        if (responseNode.hasNonNull("Error")) {
            return new CaptchaVerificationResult(false, "api-error", null,
                    responseNode.path("Error").path("Code").asText(null), responseNode.path("RequestId").asText(null));
        }

        JsonNode codeNode = responseNode.get("CaptchaCode");
        if (codeNode == null || !codeNode.isIntegralNumber() || !codeNode.canConvertToInt()) {
            return rejected("invalid-response", null);
        }
        int code = codeNode.intValue();
        if (code != 1) {
            return new CaptchaVerificationResult(false, "captcha-rejected", code, null,
                    responseNode.path("RequestId").asText(null));
        }
        return new CaptchaVerificationResult(true, "accepted", code);
    }

    private String payload(String ticket, String randstr, String userIp) throws JsonProcessingException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("AppSecretKey", secrets.captchaAppSecretKey());
        payload.put("CaptchaAppId", new BigInteger(secrets.captchaAppId()));
        payload.put("CaptchaType", 9);
        payload.put("Randstr", randstr);
        payload.put("Ticket", ticket);
        payload.put("UserIp", userIp);
        return JSON.writeValueAsString(payload);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CaptchaVerificationResult rejected(String category, Integer code) {
        return new CaptchaVerificationResult(false, category, code);
    }
}
