package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

class TencentCaptchaAuthenticatorFactoryTest {
    @Test
    void exposesExactProviderContract() {
        TencentCaptchaAuthenticatorFactory factory = new TencentCaptchaAuthenticatorFactory();

        assertEquals("tencent-captcha", factory.getId());
        assertArrayEquals(new Requirement[]{Requirement.REQUIRED}, factory.getRequirementChoices());
        assertFalse(factory.isConfigurable());
        assertFalse(factory.isUserSetupAllowed());
        assertTrue(factory.getConfigProperties().isEmpty());
        assertNotNull(factory.getDisplayType());
        assertNotNull(factory.getHelpText());
    }

    @Test
    void returnsDefensiveRequirementChoiceArrays() {
        TencentCaptchaAuthenticatorFactory factory = new TencentCaptchaAuthenticatorFactory();

        Requirement[] first = factory.getRequirementChoices();
        Requirement[] second = factory.getRequirementChoices();

        assertNotSame(first, second);
        first[0] = Requirement.DISABLED;
        assertArrayEquals(new Requirement[]{Requirement.REQUIRED}, second);
    }

    @Test
    void resolvesTrimmedSystemPropertyBeforeEnvironmentAndDefault() {
        assertEquals(Path.of("/custom/secret"),
                TencentCaptchaAuthenticator.resolveSecretPath(" /custom/secret ", "/ignored"));
        assertEquals(Path.of("/environment/secret"),
                TencentCaptchaAuthenticator.resolveSecretPath("  ", " /environment/secret "));
        assertEquals(Path.of("/run/secrets/tencent-captcha.env"),
                TencentCaptchaAuthenticator.resolveSecretPath(null, "  "));
    }

    @Test
    void lifecycleAndCreateDoNotReadSecrets() {
        AtomicInteger reads = new AtomicInteger();
        TencentCaptchaAuthenticatorFactory factory = new TencentCaptchaAuthenticatorFactory(() -> {
            reads.incrementAndGet();
            throw new AssertionError("secret file read during factory lifecycle");
        });

        factory.init(mock(Config.Scope.class));
        factory.postInit(mock(KeycloakSessionFactory.class));
        assertNotNull(factory.create(mock(KeycloakSession.class)));
        factory.close();

        assertEquals(0, reads.get());
    }
}
