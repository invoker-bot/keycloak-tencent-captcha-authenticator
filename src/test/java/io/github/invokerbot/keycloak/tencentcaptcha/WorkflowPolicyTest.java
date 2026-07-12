package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowPolicyTest {
    private static final Path PROJECT = Path.of(System.getProperty("project.basedir", System.getProperty("user.dir")));
    private static final List<String> WORKFLOWS = List.of("ci.yml", "codeql.yml", "secret-scan.yml", "release.yml");
    private static final List<String> PR_WORKFLOWS = List.of("ci.yml", "codeql.yml", "secret-scan.yml");
    private static final Pattern ACTION = Pattern.compile("(?m)^\\s*uses:\\s*([^@\\s]+)@([^#\\s]+)");
    private static final Pattern WRITE_PERMISSION = Pattern.compile("(?m)^\\s+[a-z-]+:\\s*write\\s*$");
    private static final Pattern COMMAND_LINE_SECRET = Pattern.compile(
            "(?i)(?:run:|\\n\\s+)[^\\r\\n]*(?:CAPTCHA_APP_SECRET_KEY|TENCENT_SECRET_ID|TENCENT_SECRET_KEY)\\s*=");

    @Test
    void workflowsUseImmutableActionsAndSafeEvents() throws IOException {
        for (String workflow : WORKFLOWS) {
            String text = readWorkflow(workflow);
            assertTrue(text.contains("permissions:"), workflow + " must declare permissions");
            assertFalse(text.contains("pull_request_target"), workflow);
            assertFalse(COMMAND_LINE_SECRET.matcher(text).find(),
                    workflow + " must not place secrets on command lines");
            Matcher actions = ACTION.matcher(text);
            int actionCount = 0;
            while (actions.find()) {
                actionCount++;
                assertTrue(actions.group(2).matches("[0-9a-f]{40}"), workflow + ": " + actions.group());
            }
            assertTrue(actionCount > 0, workflow + " must use pinned actions");
        }
    }

    @Test
    void pullRequestWorkflowsAreReadOnlyAtTopLevel() throws IOException {
        for (String workflow : PR_WORKFLOWS) {
            String text = readWorkflow(workflow);
            String topLevel = text.substring(0, text.indexOf("\njobs:"));
            assertTrue(topLevel.contains("permissions:\n  contents: read"), workflow);
            assertFalse(WRITE_PERMISSION.matcher(topLevel).find(), workflow + " top-level permissions");
        }
    }

    @Test
    void continuousIntegrationUsesPinnedToolchainsCachesAndAllGates() throws IOException {
        String ci = readWorkflow("ci.yml");
        assertContainsAll("ci.yml", ci, "pull_request:", "push:", "branches: [main]", "timeout-minutes:",
                "distribution: temurin", "java-version: '21'", "node-version: '20'", "python-version: '3.12'",
                "actions/cache", "hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties')", "npm ci",
                "npm test", "./mvnw -B verify", "python3 tests/integration/run.py");
        assertTrue(ci.indexOf("npm ci") < ci.indexOf("npm test"));
        assertTrue(ci.indexOf("./mvnw -B verify") < ci.indexOf("python3 tests/integration/run.py"));
    }

    @Test
    void codeqlUsesAdvancedJavaAndJavascriptAnalysisWithExactUploadPermissions() throws IOException {
        String codeql = readWorkflow("codeql.yml");
        assertContainsAll("codeql.yml", codeql, "pull_request:", "push:", "schedule:", "language: [java, javascript]",
                "github/codeql-action/init", "github/codeql-action/analyze",
                "build-mode: ${{ matrix.language == 'java' && 'manual' || 'none' }}", "./mvnw -B -DskipTests package");
        assertTrue(codeql.contains("github.event_name == 'pull_request'"));
        assertTrue(codeql.contains("github.event_name != 'pull_request'"));
        String pullRequestJob = jobSection(codeql, "analyze-pull-request");
        assertEquals("contents: read\nsecurity-events: write", normalizedPermissions(pullRequestJob),
                "the PR CodeQL job needs only the permissions required to upload its analysis");

        String trustedRefJob = jobSection(codeql, "analyze-trusted-ref");
        assertEquals("contents: read\nsecurity-events: write", normalizedPermissions(trustedRefJob),
                "the trusted CodeQL job needs only the permissions required to upload its analysis");
    }

    @Test
    void gitleaksScansPullRequestsAndFullHistory() throws IOException {
        String scan = readWorkflow("secret-scan.yml");
        assertContainsAll("secret-scan.yml", scan, "pull_request:", "push:", "fetch-depth: 0",
                "GITLEAKS_VERSION: '8.30.1'",
                "GITLEAKS_SHA256: '551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb'",
                "archive=\"gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz\"",
                "releases/download/v${GITLEAKS_VERSION}/${archive}", "sha256sum", "--check",
                "gitleaks git --redact --log-opts=--all");
        assertFalse(scan.contains("gitleaks/gitleaks-action"),
                "the action default scan range must not replace the explicit full-history CLI invocation");
    }

    @Test
    void releaseIsTagOnlyVerifiesBeforePackagingAndPublishesExactSupplyChainAssets() throws IOException {
        String release = readWorkflow("release.yml");
        assertContainsAll("release.yml", release, "tags: ['v*']", "contents: write", "id-token: write",
                "attestations: write", "java-version: '21'", "node-version: '20'", "npm ci", "npm test",
                "actions: read", "./mvnw -B verify", "python3 tests/integration/run.py",
                "python3 tests/integration/run_public_example.py", "project.version", "GITHUB_REF_NAME",
                "scripts/verify_release_ref.py", "xml.etree.ElementTree", "scripts/release_artifacts.py",
                "scripts/verify_release_artifacts.py", "SHA256SUMS", ".cdx.json", "actions/attest-build-provenance",
                "gh release create", "--generate-notes");
        assertTrue(release.indexOf("npm test") < release.indexOf("scripts/release_artifacts.py"));
        assertTrue(release.indexOf("./mvnw -B verify") < release.indexOf("scripts/release_artifacts.py"));
        assertTrue(release.indexOf("tests/integration/run.py") < release.indexOf("scripts/release_artifacts.py"));
        assertTrue(
                release.indexOf("scripts/verify_release_ref.py") < release.indexOf("actions/attest-build-provenance"));
        assertTrue(release.indexOf("scripts/verify_release_ref.py") < release.indexOf("gh release create"));
        assertFalse(release.contains("help:evaluate"),
                "release version extraction must not resolve an unpinned plugin");
        assertFalse(Pattern.compile("(?i)(?:docker\\s+(?:build|push)|docker/build-push-action|packages:\\s*write)")
                .matcher(release).find(), "release must not publish a container image");
    }

    @Test
    void ciRunsTheBoundedPublicComposeExample() throws IOException {
        String ci = readWorkflow("ci.yml");
        assertContainsAll("ci.yml", ci, "python3 tests/integration/run_public_example.py");
        assertTrue(ci.indexOf("./mvnw -B verify") < ci.indexOf("tests/integration/run_public_example.py"));
    }

    @Test
    void dependabotCoversEverySupplyChainWeekly() throws IOException {
        String dependabot = Files.readString(PROJECT.resolve(".github/dependabot.yml"));
        for (Map.Entry<String, String> ecosystem : Map.of("maven", "/", "npm", "/", "github-actions", "/").entrySet()) {
            assertTrue(dependabot.contains("package-ecosystem: \"" + ecosystem.getKey() + "\""), ecosystem.getKey());
        }
        assertEquals(3, dependabot.split("interval: \"weekly\"", -1).length - 1);
    }

    private static String readWorkflow(String name) throws IOException {
        Path workflow = PROJECT.resolve(".github/workflows").resolve(name);
        assertTrue(Files.isRegularFile(workflow), workflow.toString());
        return Files.readString(workflow);
    }

    private static void assertContainsAll(String file, String text, String... expected) {
        for (String value : expected) {
            assertTrue(text.contains(value), file + ": " + value);
        }
    }

    private static String jobSection(String workflow, String jobName) {
        Pattern jobStart = Pattern.compile("(?m)^  " + Pattern.quote(jobName) + ":\\s*$");
        Matcher matcher = jobStart.matcher(workflow);
        assertTrue(matcher.find(), "missing job: " + jobName);
        int start = matcher.start();
        Matcher nextJob = Pattern.compile("(?m)^  [a-zA-Z0-9_-]+:\\s*$").matcher(workflow);
        nextJob.region(matcher.end(), workflow.length());
        return workflow.substring(start, nextJob.find() ? nextJob.start() : workflow.length());
    }

    private static String normalizedPermissions(String job) {
        Matcher permissions = Pattern
                .compile("(?m)^    permissions:[ \\t]*\\R((?:      [a-z-]+:[ \\t]*\\S+[ \\t]*\\R?)+)").matcher(job);
        assertTrue(permissions.find(), "job must declare an explicit permissions map");
        return permissions.group(1).lines().map(String::trim).filter(line -> !line.isEmpty()).sorted()
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }
}
