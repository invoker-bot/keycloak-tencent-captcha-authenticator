package io.github.invokerbot.keycloak.tencentcaptcha;

import java.util.List;
import java.util.Objects;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

public final class TencentCaptchaAuthenticatorFactory implements AuthenticatorFactory {
    public static final String PROVIDER_ID = "tencent-captcha";
    private static final Requirement[] REQUIREMENTS = {Requirement.REQUIRED};

    private final TencentCaptchaAuthenticator.SecretSource secretSource;

    public TencentCaptchaAuthenticatorFactory() {
        this(() -> CaptchaSecrets.load(TencentCaptchaAuthenticator.resolveSecretPath()));
    }

    TencentCaptchaAuthenticatorFactory(TencentCaptchaAuthenticator.SecretSource secretSource) {
        this.secretSource = Objects.requireNonNull(secretSource, "secret-source-missing");
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return TencentCaptchaAuthenticator.createDefault(secretSource);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Tencent CAPTCHA";
    }

    @Override
    public String getReferenceCategory() {
        return "captcha";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENTS.clone();
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Validates a Tencent CAPTCHA proof before the next browser login execution.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public void init(Config.Scope config) {
        // Secrets are loaded only when an authentication reaches this execution.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No startup work.
    }

    @Override
    public void close() {
        // No retained resources.
    }
}
