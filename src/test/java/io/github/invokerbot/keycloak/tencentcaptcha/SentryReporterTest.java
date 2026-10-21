package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SentryReporterTest {
    private static final String DSN = "https://" + "a".repeat(32) + "@sentry.example.test/prefix/7";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);
    private static final CaptchaVerificationResult API_FAILURE = new CaptchaVerificationResult(false, "api-error", null,
            "AuthFailure.SecretIdNotFound", "00000000-0000-4000-8000-000000000001");

    @Test
    void configurationIsOptInAndEnvironmentOverridesBuildDefaultsIncludingExplicitDisable() throws Exception {
        Properties defaults = new Properties();
        assertNull(SentryConfiguration.resolve(Map.of(), defaults));
        defaults.setProperty("SENTRY_DSN", DSN);
        defaults.setProperty("SENTRY_ENVIRONMENT", "development");
        SentryConfiguration configuration = SentryConfiguration.resolve(Map.of("SENTRY_ENVIRONMENT", "production"),
                defaults);
        assertEquals(URI.create("https://sentry.example.test/prefix/api/7/envelope/"), configuration.endpoint());
        assertEquals("production", configuration.environment());
        assertFalse(configuration.toString().contains("a".repeat(32)));
        assertNull(SentryConfiguration.resolve(Map.of("SENTRY_DSN", ""), defaults));
    }

    @Test
    void rejectsMalformedInsecureAndSecretKeyDsns() {
        for (String dsn : List.of("http://" + "a".repeat(32) + "@sentry.example.test/7", DSN + "?secret=value",
                DSN + "#fragment", "https://key:secret@sentry.example.test/7", "https://sentry.example.test/7")) {
            assertThrows(Exception.class,
                    () -> SentryConfiguration.resolve(Map.of("SENTRY_DSN", dsn), new Properties()));
        }
    }

    @Test
    void emitsParseableEnvelopeWithOnlySanitizedFailureFields() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SentryReporter reporter = new SentryReporter(configuration(), request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(response(200, Map.of()));
        }, CLOCK);

        reporter.send(API_FAILURE, "0123456789abcdef").join();

        assertEquals(1, requests.size());
        HttpRequest request = requests.getFirst();
        assertEquals(Duration.ofSeconds(3), request.timeout().orElseThrow());
        assertEquals("application/x-sentry-envelope", request.headers().firstValue("Content-Type").orElseThrow());
        String body = TencentCaptchaVerifierTest.body(request);
        String[] lines = body.split("\n");
        ObjectMapper json = new ObjectMapper();
        JsonNode header = json.readTree(lines[0]);
        JsonNode item = json.readTree(lines[1]);
        JsonNode event = json.readTree(lines[2]);
        assertEquals("event", item.path("type").asText());
        assertEquals(lines[2].getBytes(java.nio.charset.StandardCharsets.UTF_8).length, item.path("length").asInt());
        assertEquals(header.path("event_id"), event.path("event_id"));
        assertEquals("AuthFailure.SecretIdNotFound", event.path("tags").path("api_error_code").asText());
        assertEquals(API_FAILURE.requestId(), event.path("extra").path("tencent_request_id").asText());
        assertEquals("0123456789abcdef", event.path("extra").path("correlation").asText());
        assertFalse(body.contains("a".repeat(32)));
        for (String key : List.of("user", "request", "exception", "breadcrumbs", "server_name"))
            assertFalse(event.has(key));
    }

    @Test
    void skipsSuccessfulMalformedAndUnconfiguredEventsAndDropsRawIdentifiers() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SentryReporter reporter = new SentryReporter(configuration(), request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(response(200, Map.of()));
        }, CLOCK);
        reporter.send(new CaptchaVerificationResult(true, "accepted", 1), "secret").join();
        reporter.send(new CaptchaVerificationResult(false, "missing-proof", null), "secret").join();
        reporter.send(new CaptchaVerificationResult(false, "unbounded-input", null), "secret").join();
        assertTrue(requests.isEmpty());
        new SentryReporter(null, request -> {
            fail("disabled reporting sent a request");
            return null;
        }, CLOCK).send(API_FAILURE, "secret").join();
        reporter.send(API_FAILURE, "raw-session-secret").join();
        assertFalse(TencentCaptchaVerifierTest.body(requests.getFirst()).contains("raw-session-secret"));
    }

    @Test
    void samplesEachCategoryPerMinuteAndLimitsInFlightRequestsWithoutBlocking() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<HttpResponse<Void>> pending = new CompletableFuture<>();
        SentryReporter reporter = new SentryReporter(configuration(), request -> {
            calls.incrementAndGet();
            return pending;
        }, CLOCK);
        CompletableFuture<Void> first = reporter.send(API_FAILURE, null);
        reporter.send(API_FAILURE, null).join();
        CompletableFuture<Void> second = reporter.send(new CaptchaVerificationResult(false, "transport-error", null),
                null);
        reporter.send(new CaptchaVerificationResult(false, "invalid-response", null), null).join();
        assertEquals(2, calls.get());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        pending.complete(response(200, Map.of()));
        first.join();
        second.join();
        reporter.send(new CaptchaVerificationResult(false, "captcha-rejected", 15), null).join();
        assertEquals(3, calls.get());
    }

    @Test
    void respectsRetryAfterAndResumesReportingAfterBackoff() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(CLOCK.millis());
        when(clock.instant()).thenReturn(CLOCK.instant());
        AtomicInteger calls = new AtomicInteger();
        SentryReporter reporter = new SentryReporter(configuration(), request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response(429, Map.of("Retry-After", List.of("120"))));
        }, clock);
        reporter.send(API_FAILURE, null).join();
        when(clock.millis()).thenReturn(CLOCK.millis() + 61_000);
        reporter.send(API_FAILURE, null).join();
        assertEquals(1, calls.get());
        when(clock.millis()).thenReturn(CLOCK.millis() + 121_000);
        reporter.send(API_FAILURE, null).join();
        assertEquals(2, calls.get());
    }

    @Test
    void telemetryTransportFailuresAreContained() throws Exception {
        new SentryReporter(configuration(),
                request -> CompletableFuture.failedFuture(new IllegalStateException("secret")), CLOCK)
                .send(API_FAILURE, null).join();
        new SentryReporter(configuration(), request -> {
            throw new IllegalStateException("secret");
        }, CLOCK).send(API_FAILURE, null).join();
    }

    private static SentryConfiguration configuration() throws Exception {
        return SentryConfiguration.resolve(Map.of("SENTRY_DSN", DSN, "SENTRY_ENVIRONMENT", "test"), new Properties());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> response(int status, Map<String, List<String>> headers) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (key, value) -> true));
        return response;
    }
}
