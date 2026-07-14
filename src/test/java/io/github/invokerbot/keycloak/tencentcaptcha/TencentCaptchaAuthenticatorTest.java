package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.common.ClientConnection;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.headers.SecurityHeadersOptions;
import org.keycloak.headers.SecurityHeadersProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.mockito.ArgumentCaptor;

class TencentCaptchaAuthenticatorTest {
    private static final String SCRIPT_URL = "https://turing.captcha.qcloud.com/TJCaptcha.js";
    private static final String ORIGIN = "https://turing.captcha.qcloud.com";
    private static final String DYNAMIC_SCRIPT_ORIGIN = "https://turing.captcha.gtimg.com";
    private static final CaptchaSecrets SECRETS = new CaptchaSecrets("123456789", "app-secret", "secret-id",
            "secret-key");

    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_750_000_000L), ZoneOffset.UTC);
    private AuthenticationFlowContext context;
    private LoginFormsProvider form;
    private AuthenticationSessionModel authenticationSession;
    private UserModel user;
    private SecurityHeadersOptions securityHeadersOptions;
    private Response captchaResponse;

    @BeforeEach
    void setUp() {
        context = mock(AuthenticationFlowContext.class);
        form = mock(LoginFormsProvider.class);
        authenticationSession = mock(AuthenticationSessionModel.class);
        user = mock(UserModel.class);
        RealmModel realm = mock(RealmModel.class);
        KeycloakSession keycloakSession = mock(KeycloakSession.class);
        SecurityHeadersProvider securityHeadersProvider = mock(SecurityHeadersProvider.class);
        securityHeadersOptions = mock(SecurityHeadersOptions.class);
        captchaResponse = Response.ok("captcha").type("text/html").build();

        when(context.form()).thenReturn(form);
        when(context.getAuthenticationSession()).thenReturn(authenticationSession);
        when(context.getUser()).thenReturn(user);
        when(context.getRealm()).thenReturn(realm);
        when(context.getSession()).thenReturn(keycloakSession);
        when(keycloakSession.getProvider(SecurityHeadersProvider.class)).thenReturn(securityHeadersProvider);
        when(securityHeadersProvider.options()).thenReturn(securityHeadersOptions);
        when(realm.getBrowserSecurityHeaders()).thenReturn(Map.of("contentSecurityPolicy",
                "frame-src 'self'; frame-ancestors 'self'; object-src 'none'", "xFrameOptions", "SAMEORIGIN"));
        when(form.setAttribute(anyString(), any())).thenReturn(form);
        when(form.setError(anyString(), any(Object[].class))).thenReturn(form);
        when(form.createForm("tencent-captcha.ftl")).thenReturn(captchaResponse);
    }

    @Test
    void loadsSecretsLazilyWhenExecutionRuns() {
        AtomicInteger reads = new AtomicInteger();
        TencentCaptchaAuthenticator authenticator = authenticator(() -> {
            reads.incrementAndGet();
            return SECRETS;
        }, acceptedVerifier());

        assertEquals(0, reads.get());
        authenticator.authenticate(context);

        assertEquals(1, reads.get());
    }

    @Test
    void challengeCarriesPublicAttributesAndRequestScopedSecurityHeaders() {
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, acceptedVerifier());

        authenticator.authenticate(context);

        verify(form).setAttribute("captchaAppId", "123456789");
        verify(form).setAttribute("aidEncrypted", AidEncryptedGenerator.generate("123456789", "app-secret",
                clock.instant(), Duration.ofSeconds(300), new byte[16]));
        verify(form).setAttribute("captchaScriptUrl", SCRIPT_URL);
        ArgumentCaptor<String> nonce = ArgumentCaptor.forClass(String.class);
        verify(form).setAttribute(eq("cspNonce"), nonce.capture());
        assertTrue(nonce.getValue().matches("[A-Za-z0-9_-]{22}"));

        ArgumentCaptor<Response> challenge = ArgumentCaptor.forClass(Response.class);
        verify(context).challenge(challenge.capture());
        String csp = challenge.getValue().getHeaderString("Content-Security-Policy");
        assertEquals(List.of("'self'", "'nonce-" + nonce.getValue() + "'", ORIGIN, DYNAMIC_SCRIPT_ORIGIN),
                directiveSources(csp, "script-src"));
        assertEquals(List.of("'self'", ORIGIN), directiveSources(csp, "frame-src"));
        assertEquals(List.of(ORIGIN), directiveSources(csp, "connect-src"));
        assertEquals(List.of("'self'", "blob:"), directiveSources(csp, "worker-src"));
        assertFalse(directiveSources(csp, "frame-src").contains(DYNAMIC_SCRIPT_ORIGIN));
        assertFalse(directiveSources(csp, "connect-src").contains(DYNAMIC_SCRIPT_ORIGIN));
        assertEquals("SAMEORIGIN", challenge.getValue().getHeaderString("X-Frame-Options"));
        assertFalse(csp.contains("unsafe-inline"));
        assertFalse(csp.contains("unsafe-eval"));
        assertFalse(csp.contains("*"));
        verify(securityHeadersOptions).skipHeaders();
        verifyNoPersistenceWrites();
    }

    @Test
    void rejectsUnsafeOrAmbiguousRealmPolicies() {
        for (String forbidden : new String[]{"script-src *", "script-src 'unsafe-inline'", "script-src 'unsafe-eval'",
                "script-src 'self'; SCRIPT-SRC 'none'"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TencentCaptchaAuthenticator.captchaContentSecurityPolicy(forbidden, "A".repeat(22)));
        }
    }

    @Test
    void missingTargetDirectivesInheritDefaultWithoutAddingSelf() {
        String nonce = "A".repeat(22);

        String csp = TencentCaptchaAuthenticator.captchaContentSecurityPolicy("default-src 'none'; object-src 'none'",
                nonce);

        assertEquals(List.of("'none'", "'nonce-" + nonce + "'", ORIGIN, DYNAMIC_SCRIPT_ORIGIN),
                directiveSources(csp, "script-src"));
        assertEquals(List.of("'none'", ORIGIN), directiveSources(csp, "frame-src"));
        assertEquals(List.of("'none'", ORIGIN), directiveSources(csp, "connect-src"));
        assertEquals(List.of("'self'", "blob:"), directiveSources(csp, "worker-src"));
        assertFalse(directiveSources(csp, "frame-src").contains(DYNAMIC_SCRIPT_ORIGIN));
        assertFalse(directiveSources(csp, "connect-src").contains(DYNAMIC_SCRIPT_ORIGIN));
        assertFalse(directiveSources(csp, "script-src").contains("'self'"));
        assertFalse(directiveSources(csp, "frame-src").contains("'self'"));
        assertFalse(directiveSources(csp, "connect-src").contains("'self'"));
    }

    @Test
    void missingScriptAndDefaultDirectivesAllowSameOriginThemeModules() {
        String nonce = "A".repeat(22);

        String csp = TencentCaptchaAuthenticator
                .captchaContentSecurityPolicy("frame-src 'self'; frame-ancestors 'self'; object-src 'none'", nonce);

        assertEquals(List.of("'self'", "'nonce-" + nonce + "'", ORIGIN, DYNAMIC_SCRIPT_ORIGIN),
                directiveSources(csp, "script-src"));
    }

    @Test
    void missingFrameDirectiveInheritsChildSourcesBeforeDefaultSources() {
        String csp = TencentCaptchaAuthenticator.captchaContentSecurityPolicy(
                "default-src 'none'; child-src https://frames.example.test", "A".repeat(22));

        assertTrue(csp.contains("frame-src https://frames.example.test " + ORIGIN));
        assertFalse(csp.contains("frame-src 'none'"));
    }

    private static List<String> directiveSources(String policy, String directiveName) {
        return Arrays.stream(policy.split(";")).map(String::strip).map(directive -> directive.split("\\s+"))
                .filter(tokens -> tokens.length > 0 && tokens[0].equalsIgnoreCase(directiveName))
                .map(tokens -> List.of(Arrays.copyOfRange(tokens, 1, tokens.length))).findFirst()
                .orElseThrow(() -> new AssertionError("missing CSP directive: " + directiveName));
    }

    @Test
    void configurationFailureUsesGeneric503Challenge() throws IOException {
        Response unavailable = Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        when(form.createErrorPage(Response.Status.SERVICE_UNAVAILABLE)).thenReturn(unavailable);
        TencentCaptchaAuthenticator authenticator = authenticator(() -> {
            throw new IOException("sensitive filesystem detail");
        }, acceptedVerifier());

        authenticator.authenticate(context);

        verify(form).setError(eq("captchaConfigUnavailable"), any(Object[].class));
        verify(form).createErrorPage(Response.Status.SERVICE_UNAVAILABLE);
        verify(form, never()).createForm("tencent-captcha.ftl");
        verify(context).failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, unavailable);
        verify(context, never()).success();
    }

    @Test
    void acceptedProofSucceedsUsingOnlyDecodedFieldsAndConnectionAddress() {
        TencentCaptchaAuthenticator.CaptchaProofVerifier verifier = mock(
                TencentCaptchaAuthenticator.CaptchaProofVerifier.class);
        when(verifier.verify("ticket", "rand", "2001:db8::1"))
                .thenReturn(new CaptchaVerificationResult(true, "accepted", 1));
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, verifier);
        prepareAction(" ticket ", " rand ", " 2001:db8::1 ");

        authenticator.action(context);

        verify(verifier).verify("ticket", "rand", "2001:db8::1");
        verify(context).success();
        verify(context, never()).failureChallenge(any(), any());
        verify(form, never()).createForm("tencent-captcha.ftl");
        verifyNoPersistenceWrites();
    }

    @Test
    void bulkheadBoundsConcurrentVerificationAndRejectsWithoutWaiting() throws Exception {
        VerificationBulkhead bulkhead = new VerificationBulkhead(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximumInFlight = new AtomicInteger();
        TencentCaptchaAuthenticator.CaptchaProofVerifier verifier = (ticket, randstr, userIp) -> {
            calls.incrementAndGet();
            int current = inFlight.incrementAndGet();
            maximumInFlight.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new CaptchaVerificationResult(true, "accepted", 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                inFlight.decrementAndGet();
            }
        };
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, verifier, bulkhead);
        prepareAction("ticket", "rand", "203.0.113.8");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> authenticator.action(context));
            Future<?> second = executor.submit(() -> authenticator.action(context));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            authenticator.action(context);
            long elapsed = System.nanoTime() - started;

            assertTrue(elapsed < Duration.ofSeconds(1).toNanos());
            assertEquals(2, calls.get());
            assertEquals(2, maximumInFlight.get());
            verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any(Response.class));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void throwingVerifierReleasesBulkheadPermit() {
        AtomicInteger calls = new AtomicInteger();
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, (ticket, randstr, userIp) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("raw provider body");
            }
            return new CaptchaVerificationResult(true, "accepted", 1);
        }, new VerificationBulkhead(1));
        prepareAction("ticket", "rand", "203.0.113.8");

        authenticator.action(context);
        authenticator.action(context);

        assertEquals(2, calls.get());
        verify(context).success();
    }

    @Test
    void nullVerifierResultReleasesBulkheadPermit() {
        AtomicInteger calls = new AtomicInteger();
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, (ticket, randstr,
                userIp) -> calls.incrementAndGet() == 1 ? null : new CaptchaVerificationResult(true, "accepted", 1),
                new VerificationBulkhead(1));
        prepareAction("ticket", "rand", "203.0.113.8");

        authenticator.action(context);
        authenticator.action(context);

        assertEquals(2, calls.get());
        verify(context).success();
    }

    @Test
    void acceptedResultsLogAtInfoAndAttackerTriggeredResultsLogAtDebug() {
        assertEquals(System.Logger.Level.INFO,
                TencentCaptchaAuthenticator.resultLogLevel(new CaptchaVerificationResult(true, "accepted", 1)));
        for (String category : new String[]{"missing-proof", "captcha-rejected", "busy", "transport-error",
                "invalid-response"}) {
            assertEquals(System.Logger.Level.DEBUG,
                    TencentCaptchaAuthenticator.resultLogLevel(new CaptchaVerificationResult(false, category, null)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-proof", "proof-too-large", "disaster-ticket", "transport-error",
            "invalid-response", "api-error", "captcha-rejected", "busy"})
    void everyRejectedVerificationCategoryFailsClosedAndReChallenges(String category) {
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS,
                (ticket, randstr, userIp) -> new CaptchaVerificationResult(false, category, 7));
        prepareAction("ticket", "rand", "203.0.113.8");

        authenticator.action(context);

        verify(context, never()).success();
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any(Response.class));
        verify(form).setError(eq("captchaVerificationFailed"), any(Object[].class));
        verify(form).createForm("tencent-captcha.ftl");
        verifyNoPersistenceWrites();
    }

    @Test
    void missingActionSecretAlsoUsesGeneric503Challenge() throws IOException {
        Response unavailable = Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        when(form.createErrorPage(Response.Status.SERVICE_UNAVAILABLE)).thenReturn(unavailable);
        TencentCaptchaAuthenticator authenticator = authenticator(() -> {
            throw new IOException("secret-file-missing");
        }, acceptedVerifier());

        authenticator.action(context);

        verify(context).failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, unavailable);
        verify(context, never()).success();
    }

    @Test
    void nullVerifierResultFailsClosed() {
        prepareAction("ticket", "rand", "203.0.113.8");
        authenticator(() -> SECRETS, (ticket, randstr, userIp) -> null).action(context);
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any(Response.class));
        verify(context, never()).success();
    }

    @Test
    void throwingVerifierFailsClosed() {
        prepareAction("ticket", "rand", "203.0.113.8");
        authenticator(() -> SECRETS, (ticket, randstr, userIp) -> {
            throw new IllegalStateException("raw provider body");
        }).action(context);
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any(Response.class));
        verify(context, never()).success();
    }

    @Test
    void correlationAndCategoriesAreBoundedAndRedacted() {
        RootAuthenticationSessionModel root = mock(RootAuthenticationSessionModel.class);
        when(root.getId()).thenReturn("raw-root-session-secret");
        when(authenticationSession.getParentSession()).thenReturn(root);
        when(authenticationSession.getTabId()).thenReturn("raw-tab-session-secret");

        String correlation = TencentCaptchaAuthenticator.correlation(context);

        assertEquals("invalid-result-category", TencentCaptchaAuthenticator.safeCategory("accepted\nsecret=value"));
        assertEquals("accepted", TencentCaptchaAuthenticator.safeCategory("accepted"));
        assertTrue(correlation.matches("[0-9a-f]{16}"));
        assertNotEquals("raw-root-session-secret", correlation);
        assertNotEquals("raw-tab-session-secret", correlation);
    }

    @Test
    void authenticatorIsStatelessAndHasNoUserSetup() {
        TencentCaptchaAuthenticator authenticator = authenticator(() -> SECRETS, acceptedVerifier());
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);

        assertFalse(authenticator.requiresUser());
        assertTrue(authenticator.configuredFor(session, realm, user));
        authenticator.setRequiredActions(session, realm, user);
        authenticator.close();

        verifyNoPersistenceWrites();
    }

    private TencentCaptchaAuthenticator authenticator(TencentCaptchaAuthenticator.SecretSource source,
            TencentCaptchaAuthenticator.CaptchaProofVerifier verifier) {
        return authenticator(source, verifier, new VerificationBulkhead(2));
    }

    private TencentCaptchaAuthenticator authenticator(TencentCaptchaAuthenticator.SecretSource source,
            TencentCaptchaAuthenticator.CaptchaProofVerifier verifier, VerificationBulkhead bulkhead) {
        SecureRandom random = mock(SecureRandom.class);
        return new TencentCaptchaAuthenticator(source, (ignored, client) -> verifier, () -> mock(HttpClient.class),
                clock, random, bulkhead);
    }

    private TencentCaptchaAuthenticator.CaptchaProofVerifier acceptedVerifier() {
        return (ticket, randstr, userIp) -> new CaptchaVerificationResult(true, "accepted", 1);
    }

    private void prepareAction(String ticket, String randstr, String remoteAddress) {
        HttpRequest request = mock(HttpRequest.class);
        ClientConnection connection = mock(ClientConnection.class);
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("ticket", ticket);
        parameters.putSingle("randstr", randstr);
        when(request.getDecodedFormParameters()).thenReturn(parameters);
        when(connection.getRemoteAddr()).thenReturn(remoteAddress);
        when(context.getHttpRequest()).thenReturn(request);
        when(context.getConnection()).thenReturn(connection);
    }

    private void verifyNoPersistenceWrites() {
        verify(context, never()).attempted();
        verify(authenticationSession, never()).setAuthNote(anyString(), anyString());
        verify(authenticationSession, never()).setUserSessionNote(anyString(), anyString());
        verify(user, never()).setSingleAttribute(anyString(), anyString());
        verify(user, never()).setAttribute(anyString(), any());
        verify(user, never()).credentialManager();
    }
}
