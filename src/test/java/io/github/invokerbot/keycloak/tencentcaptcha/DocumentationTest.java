package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class DocumentationTest {
    private static final Path PROJECT = Path.of(System.getProperty("project.basedir", System.getProperty("user.dir")));
    private static final List<String> PUBLIC_FILES = List.of("README.md", "README.zh-CN.md", "docs/configuration.md",
            "docs/authentication-flow.md", "docs/security-model.md", "docs/compatibility.md", "SECURITY.md",
            "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "CHANGELOG.md", ".github/ISSUE_TEMPLATE/bug_report.yml",
            ".github/ISSUE_TEMPLATE/feature_request.yml", ".github/ISSUE_TEMPLATE/config.yml",
            ".github/pull_request_template.md");
    private static final String GAV = "io.github.invoker-bot:keycloak-tencent-captcha-authenticator:0.1.0";
    private static final String JAR = "keycloak-tencent-captcha-authenticator-0.1.0.jar";
    private static final String SECRET_FILE = "/run/secrets/tencent-captcha.env";
    private static final List<String> SECRET_LINES = List.of("CAPTCHA_APP_ID=<CAPTCHA_APP_ID>",
            "CAPTCHA_APP_SECRET_KEY=<CAPTCHA_APP_SECRET_KEY>", "TENCENT_SECRET_ID=<TENCENT_SECRET_ID>",
            "TENCENT_SECRET_KEY=<TENCENT_SECRET_KEY>");
    private static final List<String> SERVER_SECRET_NAMES = List.of("CAPTCHA_APP_SECRET_KEY", "TENCENT_SECRET_ID",
            "TENCENT_SECRET_KEY");
    private static final List<String> ENV_DESTINATION_FORMS = List.of("env", "env var", "env vars");
    private static final List<String> UNSAFE_SECRET_DESTINATIONS = List.of("environment", "environment variable",
            "env_file", "env file", "env-file", "Realm attributes", "Realm configuration", "Realm export",
            "Realm JSON export", "Authenticator Config");
    private static final Pattern POSITIVE_SECRET_PLACEMENT = Pattern.compile(
            "(?i)\\b(?:use|set|put|place|store|save|supply|provide|configure|add|inject|pass|write|export|load|assign|copy|mount)\\b|使用|放入|写入|存入|设置|配置|注入|传入|导出|添加|保存|挂载");
    private static final Pattern NEGATED_SECRET_PLACEMENT = Pattern
            .compile("(?i)\\b(?:do not|don't|never|must not|not supported|not accepted)\\b|禁止|不要|不得|不可|不能|不支持|绝不|切勿");
    private static final Pattern ENV_SECRET_DESTINATION = Pattern.compile("(?i)\\benv(?:\\s+vars?)?\\b");
    private static final Pattern PROSE_CLAUSE_BOUNDARY = Pattern
            .compile("(?i)[\\n.!?。！？;；]+|\\b(?:and|but|however|yet)\\b|并且|而且|但是|然而|但|以及");

    @Test
    void publishesEveryRequiredGuideAndCommunityFile() {
        for (String file : PUBLIC_FILES) {
            assertTrue(Files.isRegularFile(PROJECT.resolve(file)), file);
        }
    }

    @Test
    void bothReadmesDescribeTheSameInstallableReleaseContract() throws IOException {
        for (String file : List.of("README.md", "README.zh-CN.md")) {
            String readme = read(file);
            assertContainsAll(file, readme, GAV, "Keycloak 26.7.0", "Java 21", JAR, "/opt/keycloak/providers/" + JAR,
                    "./mvnw -B verify", "kc.sh build", "examples/docker-compose/compose.yaml", "tencent-captcha",
                    "REQUIRED", SECRET_FILE, "docs/configuration.md", "docs/authentication-flow.md",
                    "docs/security-model.md", "docs/compatibility.md", "SECURITY.md");
        }
        assertTrue(read("README.md").contains("README.zh-CN.md"));
        assertTrue(read("README.zh-CN.md").contains("README.md"));
    }

    @Test
    void readmesExplainFlowBoundariesAndLifecycleOperations() throws IOException {
        for (String file : List.of("README.md", "README.zh-CN.md")) {
            String text = read(file);
            assertContainsAll(file, text, "Forms", "Username Password Form", "SSO Cookie", "registration", "reset",
                    "direct grant", "trusted proxy", "script-src", "frame-src", "connect-src", "Privacy", "fail-closed",
                    "upgrade", "rollback", "uninstall", "troubleshooting",
                    "docker compose -f examples/docker-compose/compose.yaml down --remove-orphans");
        }
        assertContainsAll("README.md", read("README.md"), "Remove the restricted runtime secret",
                "remove the secret-file path setting and bind-mount configuration",
                "revoke the dedicated Tencent API identity");
        assertContainsAll("README.zh-CN.md", read("README.zh-CN.md"), "删除受限 runtime secret",
                "移除 secret-file path setting 与 bind-mount 配置", "吊销专用腾讯云 API 身份");
    }

    @Test
    void documentsChallengeScopedBlobWorkerPolicy() throws IOException {
        for (String file : List.of("README.md", "README.zh-CN.md", "docs/security-model.md")) {
            assertTrue(read(file).contains("worker-src 'self' blob:"), file);
        }
    }

    @Test
    void readmesDocumentExactSecretSchemaWithoutCredentialLikeExamples() throws IOException {
        for (String file : List.of("README.md", "README.zh-CN.md")) {
            String text = read(file);
            assertContainsAll(file, text, "KC_SPI_TENCENT_CAPTCHA_SECRET_FILE", "0400", "0600", "0700", "regular",
                    "symlink", "65,536", "UTF-8", "LF");
            for (String line : SECRET_LINES) {
                assertTrue(text.contains(line), file + ": " + line);
            }
            assertFalse(Pattern
                    .compile("(?m)^(?:CAPTCHA_APP_SECRET_KEY|TENCENT_SECRET_ID|TENCENT_SECRET_KEY)=(?!<)[^\\r\\n]+$")
                    .matcher(text).find(), file);
        }
    }

    @Test
    void publicDockerSecretExampleUsesOnlyAngleBracketPlaceholders() throws IOException {
        String example = read("examples/docker-compose/secrets/tencent-captcha.env.example");
        for (String line : SECRET_LINES) {
            assertTrue(example.contains(line), line);
        }
        assertTrue(example.lines().allMatch(line -> line.matches("[A-Z_]+=<[A-Z_]+>")));
    }

    @Test
    void eachReadmeAndFocusedGuideOwnsItsSecurityContract() throws IOException {
        for (String file : List.of("README.md", "README.zh-CN.md")) {
            String text = read(file);
            assertContainsAll(file, text, "https://turing.captcha.qcloud.com/TJCaptcha.js",
                    "https://turing.captcha.gtimg.com", "https://captcha.tencentcloudapi.com", "DescribeCaptchaResult",
                    "Ticket", "Randstr", "UserIp", "CaptchaAppId", "AppSecretKey", "CaptchaCode == 1", "script-src",
                    "frame-src", "connect-src", "disaster-ticket", "errorCode", "trusted proxy", "fail-closed",
                    "client IP", "upgrade", "rollback", "uninstall");
            assertTrue(text.toLowerCase(Locale.ROOT).contains("privacy"), file + ": privacy");
        }

        assertContainsAll("docs/configuration.md", read("docs/configuration.md"), "KC_SPI_TENCENT_CAPTCHA_SECRET_FILE",
                "env_file", "Authenticator Config", "Rotation", "fail-closed");
        assertContainsAll("docs/authentication-flow.md", read("docs/authentication-flow.md"), "Forms",
                "Username Password Form", "SSO Cookie", "registration", "reset", "direct grant", "fail-closed",
                "rollback");
        String securityModel = read("docs/security-model.md");
        assertContainsAll("docs/security-model.md", securityModel, "https://turing.captcha.qcloud.com/TJCaptcha.js",
                "https://turing.captcha.gtimg.com", "https://captcha.tencentcloudapi.com", "Ticket", "Randstr",
                "UserIp", "CaptchaAppId", "AppSecretKey", "script-src", "frame-src", "connect-src", "disaster-ticket",
                "errorCode", "trusted proxy", "fail-closed", "client IP");
        assertTrue(securityModel.toLowerCase(Locale.ROOT).contains("privacy"), "docs/security-model.md: privacy");
    }

    @Test
    void documentsTc3CredentialIdentifierAndLocalSigningSecretFlow() throws IOException {
        for (String file : List.of("README.md", "docs/security-model.md")) {
            assertContainsAll(file, read(file), "TENCENT_SECRET_ID", "TC3 `Authorization` header", "Credential=",
                    "TENCENT_SECRET_KEY", "local HMAC", "never transmitted");
        }
        assertContainsAll("README.zh-CN.md", read("README.zh-CN.md"), "TENCENT_SECRET_ID", "TC3 `Authorization` header",
                "Credential=", "TENCENT_SECRET_KEY", "本地 HMAC", "不会传输");
    }

    @Test
    void publicationHasNoInternalIdentifiersOrUnsafeSecretInstructions() throws IOException {
        StringBuilder publicText = new StringBuilder();
        for (String file : PUBLIC_FILES) {
            String fileText = read(file);
            publicText.append(fileText).append('\n');
            assertNoPositiveSecretPlacementInstructions(file, fileText);
        }
        String text = publicText.toString();
        assertFalse(Pattern.compile("(?i)private\\.example|synthetic-internal/|TODO|TBD").matcher(text).find());
        assertFalse(Pattern
                .compile("(?ms)^\\s*environment:\\s*(?:\\R\\s+[^\\r\\n]+)*"
                        + "(?:CAPTCHA_APP_SECRET_KEY|TENCENT_SECRET_ID|TENCENT_SECRET_KEY)\\s*[:=]")
                .matcher(text).find());
        assertFalse(Pattern.compile("(?i)--[^\\r\\n]*(?:CAPTCHA_APP_SECRET_KEY|TENCENT_SECRET_ID|TENCENT_SECRET_KEY)=")
                .matcher(text).find());
        assertContainsAll("public identity", text, "not an official", "Tencent", "Keycloak", "Apache-2.0",
                "private vulnerability reporting", "0.1.x");
    }

    @Test
    void unsafeSecretInstructionDetectorCoversProseDestinationsAndEveryServerSecret() {
        for (String secret : SERVER_SECRET_NAMES) {
            for (String destination : UNSAFE_SECRET_DESTINATIONS) {
                String unsafeInstruction = "Store " + secret + " in " + destination + " for Keycloak.";
                assertThrows(AssertionError.class,
                        () -> assertNoPositiveSecretPlacementInstructions("synthetic", unsafeInstruction),
                        secret + " -> " + destination);
            }
        }
        assertNoPositiveSecretPlacementInstructions("synthetic",
                "Do not store TENCENT_SECRET_KEY in environment variables.");
        assertThrows(AssertionError.class, () -> assertNoPositiveSecretPlacementInstructions("synthetic",
                "Do not use defaults, but store TENCENT_SECRET_KEY in environment variables."));
    }

    @Test
    void unsafeSecretInstructionDetectorRejectsEnvEnvVarAndEnvVarsForEveryServerSecret() {
        List<Executable> assertions = SERVER_SECRET_NAMES.stream()
                .flatMap(secret -> ENV_DESTINATION_FORMS.stream().map(destination -> (Executable) () -> {
                    String unsafeInstruction = "Store " + secret + " in " + destination + ".";
                    assertThrows(AssertionError.class,
                            () -> assertNoPositiveSecretPlacementInstructions("synthetic", unsafeInstruction),
                            unsafeInstruction);
                })).toList();
        assertAll(assertions);
        assertNoPositiveSecretPlacementInstructions("synthetic",
                "Store TENCENT_SECRET_KEY in envelope metadata for audit classification.");
    }

    @Test
    void codeOfConductPublishesPrivateChannelResponseAndCorrectAttribution() throws IOException {
        String conduct = read("CODE_OF_CONDUCT.md");
        assertContainsAll("CODE_OF_CONDUCT.md", conduct, "invoker-bot@outlook.com", "5 business days", "confidential",
                "need-to-know", "Do not open a public issue", "Contributor Covenant, version 2.1",
                "Creative Commons Attribution 4.0 International", "https://creativecommons.org/licenses/by/4.0/");
        assertFalse(conduct.contains("Use GitHub's built-in **Report content**"), "CODE_OF_CONDUCT.md");

        String changelog = read("CHANGELOG.md");
        assertFalse(Pattern.compile("(?i)(?:conduct[^\\r\\n]*Apache-2\\.0|Apache-2\\.0[^\\r\\n]*conduct)")
                .matcher(changelog).find(), "CHANGELOG.md must not assign the conduct policy to Apache-2.0");
    }

    private static void assertNoPositiveSecretPlacementInstructions(String file, String text) {
        for (String statement : PROSE_CLAUSE_BOUNDARY.split(text.replace('\r', ' '))) {
            boolean namesServerSecret = SERVER_SECRET_NAMES.stream().anyMatch(statement::contains);
            boolean namesUnsafeDestination = ENV_SECRET_DESTINATION.matcher(statement).find()
                    || UNSAFE_SECRET_DESTINATIONS.stream().anyMatch(destination -> statement.toLowerCase(Locale.ROOT)
                            .contains(destination.toLowerCase(Locale.ROOT)));
            boolean directsPlacement = POSITIVE_SECRET_PLACEMENT.matcher(statement).find();
            boolean rejectsPlacement = NEGATED_SECRET_PLACEMENT.matcher(statement).find();
            assertFalse(namesServerSecret && namesUnsafeDestination && directsPlacement && !rejectsPlacement,
                    file + ": unsafe secret-placement instruction: " + statement.strip());
        }
    }

    private static void assertContainsAll(String file, String text, String... expected) {
        for (String value : expected) {
            assertTrue(text.contains(value), file + ": " + value);
        }
    }

    private static String read(String relativePath) throws IOException {
        Path path = PROJECT.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), relativePath);
        return Files.readString(path);
    }
}
