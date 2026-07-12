package io.github.invokerbot.keycloak.tencentcaptcha;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.headers.SecurityHeadersProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.BrowserSecurityHeaders;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

public final class TencentCaptchaAuthenticator implements Authenticator {
    static final String CAPTCHA_SCRIPT_URL = "https://turing.captcha.qcloud.com/TJCaptcha.js";
    static final String SECRET_FILE_SETTING = "KC_SPI_TENCENT_CAPTCHA_SECRET_FILE";
    static final Path DEFAULT_SECRET_FILE = Path.of("/run/secrets/tencent-captcha.env");

    private static final System.Logger LOGGER = System.getLogger(TencentCaptchaAuthenticator.class.getName());
    private static final Duration AID_TTL = Duration.ofSeconds(300);
    private static final int RANDOM_VALUE_LENGTH = 16;
    private static final int CORRELATION_HEX_LENGTH = 16;
    private static final String CAPTCHA_ORIGIN = "https://turing.captcha.qcloud.com";

    private final SecretSource secretSource;
    private final CaptchaVerifierFactory verifierFactory;
    private final Supplier<HttpClient> httpClientSupplier;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final VerificationBulkhead verificationBulkhead;

    TencentCaptchaAuthenticator(SecretSource secretSource, CaptchaVerifierFactory verifierFactory,
            Supplier<HttpClient> httpClientSupplier, Clock clock, SecureRandom secureRandom,
            VerificationBulkhead verificationBulkhead) {
        this.secretSource = Objects.requireNonNull(secretSource, "secret-source-missing");
        this.verifierFactory = Objects.requireNonNull(verifierFactory, "verifier-factory-missing");
        this.httpClientSupplier = Objects.requireNonNull(httpClientSupplier, "http-client-supplier-missing");
        this.clock = Objects.requireNonNull(clock, "clock-missing");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secure-random-missing");
        this.verificationBulkhead = Objects.requireNonNull(verificationBulkhead, "verification-bulkhead-missing");
    }

    static TencentCaptchaAuthenticator createDefault(SecretSource secretSource) {
        Clock clock = Clock.systemUTC();
        return new TencentCaptchaAuthenticator(secretSource,
                (secrets, client) -> new TencentCaptchaVerifier(secrets, client, clock)::verify,
                TencentCaptchaAuthenticator::sharedHttpClient, clock, new SecureRandom(),
                VerificationBulkhead.shared());
    }

    static HttpClient sharedHttpClient() {
        return SharedHttpClientHolder.INSTANCE;
    }

    static Path resolveSecretPath() {
        return resolveSecretPath(System.getProperty(SECRET_FILE_SETTING), System.getenv(SECRET_FILE_SETTING));
    }

    static Path resolveSecretPath(String propertyValue, String environmentValue) {
        String configured = firstNonBlank(propertyValue, environmentValue);
        return configured == null ? DEFAULT_SECRET_FILE : Path.of(configured);
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary.strip();
        }
        return secondary == null || secondary.isBlank() ? null : secondary.strip();
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        Objects.requireNonNull(context, "context-missing");
        try {
            challenge(context, secretSource.load(), null, false);
        } catch (IOException | RuntimeException exception) {
            configUnavailable(context);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        Objects.requireNonNull(context, "context-missing");
        CaptchaSecrets secrets;
        try {
            secrets = secretSource.load();
        } catch (IOException | RuntimeException exception) {
            configUnavailable(context);
            return;
        }

        HttpRequest request = context.getHttpRequest();
        MultivaluedMap<String, String> parameters = request == null ? null : request.getDecodedFormParameters();
        String ticket = normalized(parameters == null ? null : parameters.getFirst("ticket"));
        String randstr = normalized(parameters == null ? null : parameters.getFirst("randstr"));
        String remoteAddress = normalized(
                context.getConnection() == null ? null : context.getConnection().getRemoteAddr());

        CaptchaVerificationResult result = rejectedBusy();
        if (verificationBulkhead.tryAcquire()) {
            try {
                result = verifierFactory.create(secrets, httpClientSupplier.get()).verify(ticket, randstr,
                        remoteAddress);
                if (result == null) {
                    result = rejectedInvalidResponse();
                }
            } catch (RuntimeException exception) {
                result = rejectedInvalidResponse();
            } finally {
                verificationBulkhead.release();
            }
        }

        logResult(context, result);
        if (result.accepted()) {
            context.success();
            return;
        }

        try {
            challenge(context, secrets, "captchaVerificationFailed", true);
        } catch (RuntimeException exception) {
            configUnavailable(context);
        }
    }

    private static CaptchaVerificationResult rejectedInvalidResponse() {
        return new CaptchaVerificationResult(false, "invalid-response", null);
    }

    private static CaptchaVerificationResult rejectedBusy() {
        return new CaptchaVerificationResult(false, "busy", null);
    }

    private void challenge(AuthenticationFlowContext context, CaptchaSecrets secrets, String errorKey,
            boolean failure) {
        byte[] iv = randomBytes();
        String cspNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        String aidEncrypted = AidEncryptedGenerator.generate(secrets.captchaAppId(), secrets.captchaAppSecretKey(),
                clock.instant(), AID_TTL, iv);

        LoginFormsProvider form = context.form().setAttribute("captchaAppId", secrets.captchaAppId())
                .setAttribute("aidEncrypted", aidEncrypted).setAttribute("captchaScriptUrl", CAPTCHA_SCRIPT_URL)
                .setAttribute("cspNonce", cspNonce);
        if (errorKey != null) {
            form.setError(errorKey);
        }
        Response response = secureCaptchaResponse(context, form.createForm("tencent-captcha.ftl"), cspNonce);
        if (failure) {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, response);
        } else {
            context.challenge(response);
        }
    }

    private byte[] randomBytes() {
        byte[] value = new byte[RANDOM_VALUE_LENGTH];
        secureRandom.nextBytes(value);
        return value;
    }

    private static Response secureCaptchaResponse(AuthenticationFlowContext context, Response response, String nonce) {
        RealmModel realm = Objects.requireNonNull(context.getRealm(), "realm-missing");
        Map<String, String> configured = realm.getBrowserSecurityHeaders();
        configured = configured == null ? Map.of() : configured;
        String baseCsp = configured.getOrDefault(BrowserSecurityHeaders.CONTENT_SECURITY_POLICY.getKey(),
                BrowserSecurityHeaders.CONTENT_SECURITY_POLICY.getDefaultValue());
        String dynamicCsp = captchaContentSecurityPolicy(baseCsp, nonce);

        Response.ResponseBuilder builder = Response.fromResponse(response);
        for (BrowserSecurityHeaders header : BrowserSecurityHeaders.values()) {
            String value = header == BrowserSecurityHeaders.CONTENT_SECURITY_POLICY
                    ? dynamicCsp
                    : configured.getOrDefault(header.getKey(), header.getDefaultValue());
            if (value != null && !value.isEmpty()) {
                builder.header(header.getHeaderName(), value);
            }
        }

        KeycloakSession session = Objects.requireNonNull(context.getSession(), "session-missing");
        SecurityHeadersProvider provider = Objects.requireNonNull(session.getProvider(SecurityHeadersProvider.class),
                "security-headers-provider-missing");
        provider.options().skipHeaders();
        return builder.build();
    }

    static String captchaContentSecurityPolicy(String policy, String nonce) {
        if (policy == null || policy.isBlank() || nonce == null || !nonce.matches("[A-Za-z0-9_-]{22}")) {
            throw new IllegalArgumentException("captcha-csp-input-invalid");
        }
        LinkedHashMap<String, Directive> directives = new LinkedHashMap<>();
        for (String raw : policy.split(";")) {
            String stripped = raw.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            String[] tokens = stripped.split("\\s+");
            String normalized = tokens[0].toLowerCase(Locale.ROOT);
            if (directives.containsKey(normalized)) {
                throw new IllegalArgumentException("captcha-csp-duplicate-directive");
            }
            List<String> sources = new ArrayList<>();
            for (int index = 1; index < tokens.length; index++) {
                String source = tokens[index];
                String folded = source.toLowerCase(Locale.ROOT);
                if (source.contains("*") || folded.equals("'unsafe-inline'") || folded.equals("'unsafe-eval'")) {
                    throw new IllegalArgumentException("captcha-csp-forbidden-source");
                }
                sources.add(source);
            }
            directives.put(normalized, new Directive(tokens[0], sources));
        }
        mergeDirective(directives, "script-src", inheritedSources(directives, "default-src"),
                List.of("'nonce-" + nonce + "'", CAPTCHA_ORIGIN));
        mergeDirective(directives, "frame-src", inheritedSources(directives, "child-src", "default-src"),
                List.of(CAPTCHA_ORIGIN));
        mergeDirective(directives, "connect-src", inheritedSources(directives, "default-src"), List.of(CAPTCHA_ORIGIN));
        return directives.values().stream()
                .map(directive -> (directive.name() + " " + String.join(" ", directive.sources())).strip())
                .reduce((left, right) -> left + "; " + right).orElseThrow();
    }

    private static List<String> inheritedSources(LinkedHashMap<String, Directive> directives, String... names) {
        for (String name : names) {
            Directive directive = directives.get(name);
            if (directive != null) {
                return directive.sources();
            }
        }
        return List.of();
    }

    private static void mergeDirective(LinkedHashMap<String, Directive> directives, String name, List<String> fallback,
            List<String> additions) {
        Directive current = directives.get(name);
        List<String> sources = new ArrayList<>(current == null ? fallback : current.sources());
        for (String addition : additions) {
            if (!sources.contains(addition)) {
                sources.add(addition);
            }
        }
        directives.put(name, new Directive(current == null ? name : current.name(), sources));
    }

    private record Directive(String name, List<String> sources) {
    }

    private static void configUnavailable(AuthenticationFlowContext context) {
        LOGGER.log(System.Logger.Level.ERROR,
                "event=tencent-captcha result=captcha-config-unavailable correlation=" + correlation(context));
        Response response = context.form().setError("captchaConfigUnavailable")
                .createErrorPage(Response.Status.SERVICE_UNAVAILABLE);
        context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, response);
    }

    private static void logResult(AuthenticationFlowContext context, CaptchaVerificationResult result) {
        StringBuilder message = new StringBuilder("event=tencent-captcha result=")
                .append(safeCategory(result.category()));
        if (result.code() != null) {
            message.append(" code=").append(result.code());
        }
        message.append(" correlation=").append(correlation(context));
        LOGGER.log(resultLogLevel(result), message.toString());
    }

    static System.Logger.Level resultLogLevel(CaptchaVerificationResult result) {
        return result.accepted() ? System.Logger.Level.INFO : System.Logger.Level.DEBUG;
    }

    static String safeCategory(String category) {
        return category != null && category.matches("[a-z0-9-]{1,40}") ? category : "invalid-result-category";
    }

    static String correlation(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel session = context.getAuthenticationSession();
            if (session == null) {
                return "unavailable";
            }
            RootAuthenticationSessionModel root = session.getParentSession();
            String rootId = root == null ? "" : Objects.toString(root.getId(), "");
            String tabId = Objects.toString(session.getTabId(), "");
            if (rootId.isEmpty() && tabId.isEmpty()) {
                return "unavailable";
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rootId + ':' + tabId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, CORRELATION_HEX_LENGTH);
        } catch (GeneralSecurityException | RuntimeException exception) {
            return "unavailable";
        }
    }

    private static String normalized(String value) {
        return value == null ? null : value.strip();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No per-user state.
    }

    @Override
    public void close() {
        // No retained resources.
    }

    @FunctionalInterface
    interface SecretSource {
        CaptchaSecrets load() throws IOException;
    }

    @FunctionalInterface
    interface CaptchaProofVerifier {
        CaptchaVerificationResult verify(String ticket, String randstr, String userIp);
    }

    @FunctionalInterface
    interface CaptchaVerifierFactory {
        CaptchaProofVerifier create(CaptchaSecrets secrets, HttpClient client);
    }

    private static final class SharedHttpClientHolder {
        private static final HttpClient INSTANCE = TencentCaptchaVerifier.newHttpClient();

        private SharedHttpClientHolder() {
        }
    }
}
