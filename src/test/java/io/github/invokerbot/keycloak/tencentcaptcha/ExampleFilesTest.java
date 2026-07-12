package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExampleFilesTest {
    private static final Path PROJECT = Path.of(System.getProperty("project.basedir", System.getProperty("user.dir")));
    private static final String SECRET_TARGET = "/run/secrets/tencent-captcha.env";
    private static final Set<String> SECRET_KEYS = Set.of("CAPTCHA_APP_ID", "CAPTCHA_APP_SECRET_KEY",
            "TENCENT_SECRET_ID", "TENCENT_SECRET_KEY");

    @Test
    void integrationStackPinsFreshKeycloakAndKeepsCredentialsSyntheticAndLocal() throws IOException {
        String dockerfile = read("tests/integration/Dockerfile");
        String compose = read("tests/integration/compose.yaml");

        assertTrue(dockerfile.contains("FROM quay.io/keycloak/keycloak:26.7.0"));
        assertTrue(dockerfile.contains("target/keycloak-tencent-captcha-authenticator-0.1.0.jar"));
        assertTrue(dockerfile.contains("/opt/keycloak/bin/kc.sh build"));
        assertTrue(compose.contains("127.0.0.1:"));
        assertTrue(compose.contains(SECRET_TARGET + ":ro"));
        assertTrue(compose.contains("KC_BOOTSTRAP_ADMIN_USERNAME: integration-admin"));
        assertTrue(compose.contains("KC_BOOTSTRAP_ADMIN_PASSWORD: integration-password-not-a-secret"));
        assertForbiddenComposeInputsAbsent(compose);
    }

    @Test
    void publicExampleUsesOnlyAReadOnlyRuntimeFileAndLoopbackPort() throws IOException {
        String dockerfile = read("examples/docker-compose/Dockerfile");
        String compose = read("examples/docker-compose/compose.yaml");

        assertTrue(dockerfile.contains("FROM quay.io/keycloak/keycloak:26.7.0"));
        assertTrue(dockerfile.contains("/opt/keycloak/bin/kc.sh build"));
        assertTrue(compose.contains("127.0.0.1:"));
        assertTrue(compose.contains(SECRET_TARGET + ":ro"));
        assertFalse(compose.contains("KC_BOOTSTRAP_ADMIN_"));
        assertForbiddenComposeInputsAbsent(compose);
    }

    @Test
    void rootDockerIgnoreExcludesSecretsAndBuildStateWithExampleOnlyExceptions() throws IOException {
        String compose = read("examples/docker-compose/compose.yaml");
        String dockerIgnore = read(".dockerignore");
        Set<String> rules = dockerIgnore.lines().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toSet());

        assertTrue(compose.contains("context: ../.."));
        assertTrue(rules.contains(".git"));
        assertTrue(rules.contains(".git/**"));
        assertTrue(rules.contains("target"));
        assertTrue(rules.contains("**/target/**"));
        assertTrue(rules.contains("!target/keycloak-tencent-captcha-authenticator-0.1.0.jar"));
        assertTrue(rules.contains(".env"));
        assertTrue(rules.contains("**/.env.*"));
        assertTrue(rules.contains("**/secrets/*"));
        assertTrue(rules.contains("!*.env.example"));
        assertTrue(rules.contains("!**/*.env.example"));
        assertFalse(rules.contains("!**/secrets/*"));
        assertFalse(rules.stream().anyMatch(rule -> rule.startsWith("!") && !rule.endsWith(".env.example")
                && !rule.equals("!target/keycloak-tencent-captcha-authenticator-0.1.0.jar")));
    }

    @Test
    void exampleSecretFixturesContainExactlySyntheticPlaceholderKeys() throws IOException {
        for (String fixture : new String[]{"tests/integration/secrets/tencent-captcha.env.example",
                "examples/docker-compose/secrets/tencent-captcha.env.example"}) {
            String content = read(fixture);
            assertEquals(SECRET_KEYS, parsedKeys(content), fixture);
            assertFalse(content.toLowerCase().contains("prod"), fixture);
            assertFalse(content.toLowerCase().contains("real-secret"), fixture);
            assertEquals(4, content.lines().count(), fixture);
            assertTrue(content.lines().allMatch(line -> line.matches("[A-Z_]+=[^=\\r\\n]+")), fixture);
        }
    }

    @Test
    void secretPreparationIsLinuxBoundedAndRejectsSymlinksBeforeRestrictedInstall() throws IOException {
        String script = read("examples/docker-compose/prepare-secret.sh");
        String helper = read("examples/docker-compose/prepare_secret.py");

        assertTrue(script.startsWith("#!/bin/sh\n"));
        assertTrue(script.contains("exec python3"));
        assertTrue(script.contains("prepare_secret.py"));
        assertFalse(script.contains("install "));
        assertTrue(helper.contains("sys.platform.startswith(\"linux\")"));
        assertTrue(helper.contains("os.O_NOFOLLOW"));
        assertTrue(helper.contains("dir_fd="));
        assertTrue(helper.contains("os.fstat("));
        assertTrue(helper.contains("os.fchown("));
        assertTrue(helper.contains("EXPECTED_UID, EXPECTED_GID"));
        assertTrue(helper.contains("os.fchmod("));
        assertTrue(helper.contains("os.replace("));
        assertTrue(helper.contains("CAPTCHA_APP_SECRET_KEY"));
        assertTrue(helper.contains("TENCENT_SECRET_KEY"));
    }

    @Test
    void acceptanceRunnerIsBoundedAndAlwaysCleansTheFreshStack() throws IOException {
        String runner = read("tests/integration/run.py");
        String verifier = read("tests/integration/configure_and_verify.py");

        assertTrue(runner.contains("subprocess.run("));
        assertTrue(runner.contains("timeout="));
        assertTrue(runner.contains("finally:"));
        assertTrue(runner.contains("down"));
        assertTrue(runner.contains("--volumes"));
        assertTrue(runner.contains("signal.setitimer"));
        assertTrue(runner.contains("WORK_BUDGET_SECONDS = 540"));
        assertTrue(runner.contains("CLEANUP_BUDGET_SECONDS = 60"));
        assertTrue(runner.contains("sys.platform.startswith(\"linux\")"));
        assertTrue(runner.contains("chown 1000:0"));
        assertTrue(runner.contains("\"--name\""));
        assertTrue(runner.contains("\"rm\", \"--force\""));
        assertTrue(runner.contains("\"container\", \"inspect\""));
        assertTrue(verifier.contains("urllib.request"));
        for (String result : new String[]{"ready", "providerDiscovered", "embeddedTemplate", "browserFlow",
                "captchaRequirement", "missingSecretFailClosed", "invalidSecretFailClosed", "challengeCspExact",
                "scriptUrlExact", "constructorOptionsAidEncryptedOnly", "loginHeadersUnchanged",
                "registrationHeadersUnchanged", "errorHeadersUnchanged", "masterHeadersUnchanged",
                "accountConsoleHeadersUnchanged"}) {
            assertTrue(runner.contains(result), result);
        }
    }

    private static void assertForbiddenComposeInputsAbsent(String compose) {
        assertFalse(Pattern.compile("(?m)^\\s*env_file\\s*:").matcher(compose).find());
        assertFalse(Pattern.compile("(?m)^\\s*args\\s*:").matcher(compose).find());
        for (String key : SECRET_KEYS) {
            assertFalse(Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*[:=]").matcher(compose).find(), key);
        }
    }

    private static Set<String> parsedKeys(String content) {
        Pattern assignment = Pattern.compile("(?m)^([A-Z_]+)=");
        Matcher matcher = assignment.matcher(content);
        return matcher.results().map(result -> result.group(1)).collect(Collectors.toSet());
    }

    private static String read(String relativePath) throws IOException {
        Path path = PROJECT.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), relativePath);
        return Files.readString(path);
    }
}
