package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class TencentCaptchaVerifierTest {
    private static final CaptchaSecrets SECRETS = new CaptchaSecrets("123456789", "CAPTCHASECRETEXAMPLE", "AKIDEXAMPLE",
            "SECRETKEYEXAMPLE");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsDisasterTicketWithoutNetworkRequest() {
        FakeHttpClient client = new FakeHttpClient(200, acceptedBody());
        TencentCaptchaVerifier verifier = new TencentCaptchaVerifier(SECRETS, client, CLOCK);

        CaptchaVerificationResult result = verifier.verify("trerror_1001_example", "@rand", "203.0.113.9");

        assertEquals(new CaptchaVerificationResult(false, "disaster-ticket", null), result);
        assertEquals(0, client.sendCount.get());
    }

    @Test
    void acceptsOnlyCodeOneAndSendsExactBoundedRequest() {
        FakeHttpClient client = new FakeHttpClient(200, acceptedBody());
        TencentCaptchaVerifier verifier = new TencentCaptchaVerifier(SECRETS, client, CLOCK);

        CaptchaVerificationResult result = verifier.verify("ticket", "rand", "203.0.113.9");

        assertEquals(new CaptchaVerificationResult(true, "accepted", 1), result);
        assertEquals(1, client.sendCount.get());
        HttpRequest request = client.lastRequest;
        assertEquals(TencentCaptchaVerifier.ENDPOINT, request.uri());
        assertEquals("POST", request.method());
        assertEquals(HttpClient.Version.HTTP_1_1, request.version().orElseThrow());
        assertEquals(Duration.ofSeconds(5), request.timeout().orElseThrow());
        assertEquals("application/json; charset=utf-8", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("DescribeCaptchaResult", request.headers().firstValue("X-TC-Action").orElseThrow());
        assertEquals("2019-07-22", request.headers().firstValue("X-TC-Version").orElseThrow());
        assertEquals("1783814400", request.headers().firstValue("X-TC-Timestamp").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").orElseThrow().startsWith("TC3-HMAC-SHA256 "));
        assertEquals("captcha.tencentcloudapi.com", request.uri().getHost());
        assertEquals("{\"AppSecretKey\":\"CAPTCHASECRETEXAMPLE\",\"CaptchaAppId\":123456789,"
                + "\"CaptchaType\":9,\"Randstr\":\"rand\",\"Ticket\":\"ticket\"," + "\"UserIp\":\"203.0.113.9\"}",
                body(request));
    }

    @Test
    void rejectsNonOneCode() {
        CaptchaVerificationResult result = verifyWith(200,
                "{\"Response\":{\"CaptchaCode\":7,\"RequestId\":\"request-id\"}}");

        assertEquals(new CaptchaVerificationResult(false, "captcha-rejected", 7), result);
    }

    @Test
    void rejectsMissingAndOversizedProofWithoutSending() {
        FakeHttpClient client = new FakeHttpClient(200, acceptedBody());
        TencentCaptchaVerifier verifier = new TencentCaptchaVerifier(SECRETS, client, CLOCK);

        assertEquals(new CaptchaVerificationResult(false, "missing-proof", null),
                verifier.verify("", "rand", "203.0.113.9"));
        assertEquals(new CaptchaVerificationResult(false, "missing-proof", null),
                verifier.verify("ticket", " ", "203.0.113.9"));
        assertEquals(new CaptchaVerificationResult(false, "missing-proof", null),
                verifier.verify("ticket", "rand", ""));
        assertEquals(new CaptchaVerificationResult(false, "proof-too-large", null),
                verifier.verify("x".repeat(8_193), "rand", "203.0.113.9"));
        assertEquals(new CaptchaVerificationResult(false, "proof-too-large", null),
                verifier.verify("ticket", "x".repeat(1_025), "203.0.113.9"));
        assertEquals(new CaptchaVerificationResult(false, "proof-too-large", null),
                verifier.verify("ticket", "rand", "x".repeat(256)));
        assertEquals(0, client.sendCount.get());
    }

    @Test
    void failsClosedForHttpApiAndInvalidResponses() {
        assertEquals("transport-error", verifyWith(503, "unavailable").category());
        assertEquals("api-error", verifyWith(200, "{\"Response\":{\"Error\":{\"Code\":\"InternalError\","
                + "\"Message\":\"redacted\"},\"RequestId\":\"id\"}}").category());
        assertEquals("invalid-response", verifyWith(200, "not-json").category());
        assertEquals("invalid-response", verifyWith(200, "{\"Response\":{}}").category());
        assertEquals("invalid-response", verifyWith(200, "{\"Response\":{\"CaptchaCode\":1.0}}").category());
        assertEquals("invalid-response", verifyWith(200, "{\"Response\":{\"CaptchaCode\":\"1\"}}").category());
    }

    @Test
    void timeoutFailsClosedWithoutRetry() {
        FakeHttpClient client = new FakeHttpClient(new HttpTimeoutException("synthetic timeout"));
        TencentCaptchaVerifier verifier = new TencentCaptchaVerifier(SECRETS, client, CLOCK);

        CaptchaVerificationResult result = verifier.verify("ticket", "rand", "203.0.113.9");

        assertFalse(result.accepted());
        assertEquals("transport-error", result.category());
        assertEquals(1, client.sendCount.get());
    }

    @Test
    void interruptedSendRestoresInterruptStatusWithoutRetry() {
        FakeHttpClient client = new FakeHttpClient(new InterruptedException("synthetic interrupt"));
        TencentCaptchaVerifier verifier = new TencentCaptchaVerifier(SECRETS, client, CLOCK);

        try {
            CaptchaVerificationResult result = verifier.verify("ticket", "rand", "203.0.113.9");

            assertFalse(result.accepted());
            assertEquals("transport-error", result.category());
            assertEquals(1, client.sendCount.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void newClientHasExactConnectTimeout() {
        assertEquals(Duration.ofSeconds(3), TencentCaptchaVerifier.newHttpClient().connectTimeout().orElseThrow());
    }

    private static CaptchaVerificationResult verifyWith(int status, String responseBody) {
        return new TencentCaptchaVerifier(SECRETS, new FakeHttpClient(status, responseBody), CLOCK).verify("ticket",
                "rand", "203.0.113.9");
    }

    private static String acceptedBody() {
        return "{\"Response\":{\"CaptchaCode\":1,\"CaptchaMsg\":\"OK\"," + "\"RequestId\":\"request-id\"}}";
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.join();
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class FakeHttpClient extends HttpClient {
        private final int status;
        private final String responseBody;
        private final IOException ioFailure;
        private final InterruptedException interruptedFailure;
        private final AtomicInteger sendCount = new AtomicInteger();
        private HttpRequest lastRequest;

        private FakeHttpClient(int status, String responseBody) {
            this.status = status;
            this.responseBody = responseBody;
            this.ioFailure = null;
            this.interruptedFailure = null;
        }

        private FakeHttpClient(IOException failure) {
            this.status = 0;
            this.responseBody = null;
            this.ioFailure = failure;
            this.interruptedFailure = null;
        }

        private FakeHttpClient(InterruptedException failure) {
            this.status = 0;
            this.responseBody = null;
            this.ioFailure = null;
            this.interruptedFailure = failure;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(3));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            sendCount.incrementAndGet();
            lastRequest = request;
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (interruptedFailure != null) {
                throw interruptedFailure;
            }
            return (HttpResponse<T>) response(request, status, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }

        private static HttpResponse<String> response(HttpRequest request, int status, String responseBody) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of(), (name, value) -> true);
                }

                @Override
                public String body() {
                    return responseBody;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public java.net.URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_2;
                }
            };
        }
    }
}
