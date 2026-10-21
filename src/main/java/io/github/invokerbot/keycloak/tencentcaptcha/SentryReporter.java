package io.github.invokerbot.keycloak.tencentcaptcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Sends only explicitly constructed, bounded diagnostics; never arbitrary
 * exceptions or request data.
 */
final class SentryReporter {
    private static final System.Logger LOGGER = System.getLogger(SentryReporter.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("api-error", "transport-error", "invalid-response",
            "captcha-config-unavailable", "busy", "captcha-rejected");
    private static final long INTERVAL_MILLIS = 60_000;

    private final SentryConfiguration configuration;
    private final Function<HttpRequest, CompletableFuture<HttpResponse<Void>>> sender;
    private final Clock clock;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(2);
    private final AtomicLong retryAfter = new AtomicLong();

    SentryReporter(SentryConfiguration configuration,
            Function<HttpRequest, CompletableFuture<HttpResponse<Void>>> sender, Clock clock) {
        this.configuration = configuration;
        this.sender = sender;
        this.clock = clock;
    }

    static void capture(CaptchaVerificationResult result, String correlation) {
        Holder.INSTANCE.send(result, correlation);
    }

    private static SentryReporter createDefault() {
        try {
            SentryConfiguration configuration = SentryConfiguration.load();
            if (configuration == null)
                return new SentryReporter(null, null, Clock.systemUTC());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            return new SentryReporter(configuration,
                    request -> client.sendAsync(request, HttpResponse.BodyHandlers.discarding()), Clock.systemUTC());
        } catch (Exception exception) {
            // DSNs and parser exception messages must never be logged.
            LOGGER.log(System.Logger.Level.WARNING, "event=tencent-captcha-sentry result=configuration-invalid");
            return new SentryReporter(null, null, Clock.systemUTC());
        }
    }

    CompletableFuture<Void> send(CaptchaVerificationResult result, String correlation) {
        long now = clock.millis();
        if (configuration == null || result.accepted() || !CATEGORIES.contains(result.category())
                || now < retryAfter.get() || !reserve(result.category(), now)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!inFlight.tryAcquire())
            return CompletableFuture.completedFuture(null);
        try {
            String eventId = UUID.randomUUID().toString().replace("-", "");
            String event = JSON.writeValueAsString(event(result, correlation, eventId));
            String envelope = JSON
                    .writeValueAsString(Map.of("event_id", eventId, "sent_at", clock.instant().toString()))
                    + "\n"
                    + JSON.writeValueAsString(
                            Map.of("type", "event", "length", event.getBytes(StandardCharsets.UTF_8).length))
                    + "\n" + event + "\n";
            HttpRequest request = HttpRequest.newBuilder(configuration.endpoint()).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-sentry-envelope")
                    .header("X-Sentry-Auth",
                            "Sentry sentry_version=7,sentry_key=" + configuration.publicKey()
                                    + ",sentry_client=keycloak-tencent-captcha/0.1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build();
            return sender.apply(request).handle((response, error) -> {
                try {
                    if (error != null || response == null || response.statusCode() < 200
                            || response.statusCode() >= 300) {
                        LOGGER.log(System.Logger.Level.WARNING, "event=tencent-captcha-sentry result=delivery-failed");
                    }
                    if (response != null && response.statusCode() == 429) {
                        long delay = 60;
                        try {
                            delay = Math.max(60, Math.min(3_600,
                                    Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"))));
                        } catch (NumberFormatException ignored) {
                            // A malformed rate limit falls back to one minute.
                        }
                        retryAfter.accumulateAndGet(clock.millis() + delay * 1_000, Math::max);
                    }
                } finally {
                    inFlight.release();
                }
                return null;
            });
        } catch (Exception exception) {
            inFlight.release();
            LOGGER.log(System.Logger.Level.WARNING, "event=tencent-captcha-sentry result=delivery-failed");
            return CompletableFuture.completedFuture(null);
        }
    }

    private synchronized boolean reserve(String category, long now) {
        Long previous = lastSent.get(category);
        if (previous != null && now - previous < INTERVAL_MILLIS)
            return false;
        lastSent.put(category, now);
        return true;
    }

    private ObjectNode event(CaptchaVerificationResult result, String correlation, String id) {
        ObjectNode event = JSON.createObjectNode();
        event.put("event_id", id);
        event.put("timestamp", clock.instant().toString());
        event.put("platform", "java");
        event.put("level", result.operationalFailure() ? "error" : "warning");
        event.put("logger", "keycloak.tencent-captcha");
        event.putObject("logentry").put("message", "Tencent CAPTCHA: " + result.category());
        if (!configuration.environment().isBlank())
            event.put("environment", configuration.environment());
        if (!configuration.release().isBlank())
            event.put("release", configuration.release());
        ObjectNode tags = event.putObject("tags");
        tags.put("component", "tencent-captcha");
        tags.put("category", result.category());
        if (result.code() != null)
            tags.put("captcha_code", result.code().toString());
        if (result.apiErrorCode() != null)
            tags.put("api_error_code", result.apiErrorCode());
        ObjectNode details = event.putObject("extra");
        if (correlation != null && correlation.matches("[a-f0-9]{16}"))
            details.put("correlation", correlation);
        if (result.requestId() != null)
            details.put("tencent_request_id", result.requestId());
        event.putArray("fingerprint").add("tencent-captcha").add(result.category())
                .add(result.apiErrorCode() != null ? result.apiErrorCode() : String.valueOf(result.code()));
        return event;
    }

    private static final class Holder {
        private static final SentryReporter INSTANCE = createDefault();
    }
}
