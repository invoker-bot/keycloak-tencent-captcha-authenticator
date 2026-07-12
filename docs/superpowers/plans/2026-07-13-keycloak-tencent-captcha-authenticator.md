# Keycloak Tencent CAPTCHA Authenticator Implementation Plan

> **Historical implementation record:** This file preserves the original task sequence and commands. Its checkboxes are not a live status tracker; Git history and required GitHub Actions are authoritative.

**Goal:** Publish `invoker-bot/keycloak-tencent-captcha-authenticator` as a secure, single-JAR, Apache-2.0 Keycloak 26.7.0 Authenticator with an embedded Tencent CAPTCHA page and a verified `v0.1.0` release.

**Architecture:** A thin Java 21 JAR implements the Keycloak Authentication SPI, Tencent TC3 verification, strict file-secret loading, request-scoped CSP handling, and embedded FreeMarker/JS/CSS/message resources. Node 20 tests the dependency-free browser module; Maven tests Java behavior; a Linux Docker acceptance harness proves provider discovery, embedded rendering, flow wiring, fail-closed secret behavior, and header isolation on a fresh Keycloak 26.7.0 image.

**Tech Stack:** Java 21, Maven Wrapper 3.9.x, Keycloak 26.7.0 SPI, JUnit 5, Mockito, Jackson from Keycloak, Node.js 20 `node:test`, Python 3 standard library for container acceptance orchestration, Docker Compose, GitHub Actions, CodeQL, Gitleaks, CycloneDX.

## Global Constraints

- Repository is public at `invoker-bot/keycloak-tencent-captcha-authenticator`; local path is `<repository-root>`; work directly on `main`.
- Maven coordinate is `io.github.invoker-bot:keycloak-tencent-captcha-authenticator:0.1.0`; Java package is `io.github.invokerbot.keycloak.tencentcaptcha`.
- Keycloak compatibility claim is exactly `26.7.0`; Java runtime baseline is exactly `21`.
- Release is one thin JAR with embedded `tencent-captcha.ftl`, JS, CSS, and `messages_en`/`messages_zh_CN`; no Tencent SDK and no Keycloakify artifact.
- Stable provider ID is `tencent-captcha`; only `REQUIRED` is supported.
- Secret path setting is `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE`; JVM property overrides environment; default is `/run/secrets/tencent-captcha.env`.
- Secret file contains exactly the four approved keys in any order, is at most 65,536 bytes, is a non-symlink regular file mode `0400` or `0600`, and has a non-symlink immediate parent not writable by group/other.
- All configuration, proof, transport, Tencent API, and parsing failures are fail-closed; there is no bypass option.
- Browser script URL is exactly `https://turing.captcha.qcloud.com/TJCaptcha.js`; server endpoint is exactly `https://captcha.tencentcloudapi.com`.
- No production hostname, internal infrastructure identifier, source-deployment history, real secret, raw proof, or raw authentication-session identifier may enter the repository or logs.
- Apache-2.0 license, clean history, English and Simplified Chinese documentation, checksums, SBOM, artifact attestation, and GitHub security settings are required before `v0.1.0`.
- Every implementation task receives a spec-compliance review followed by a code-quality review; the completed repository receives a final cross-module review.

---

### Task 1: Repository foundation and verification core

**Files:**
- Create: `.editorconfig`
- Create: `.gitignore`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `pom.xml`
- Create: `LICENSE`
- Create: `NOTICE`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/AidEncryptedGeneratorTest.java`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/CaptchaSecretsTest.java`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/Tc3SignerTest.java`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaVerifierTest.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/AidEncryptedGenerator.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/CaptchaSecrets.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/CaptchaVerificationResult.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/Tc3Signer.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaVerifier.java`

**Interfaces:**
- Consumes: the approved design spec and behavior from the source deployment only as a reference; do not copy its history, package names, comments tied to a realm, or deployment configuration.
- Produces: package-private `AidEncryptedGenerator.generate(String,String,Instant,Duration,byte[])`, `CaptchaSecrets.load(Path)`, `Tc3Signer.authorization(...)`, `CaptchaVerificationResult`, and `TencentCaptchaVerifier.verify(String,String,String)` for Task 2.

- [ ] **Step 1: Add reproducible build and licensing files**

Create a Java 21 Maven build with project version `0.1.0`, `keycloak-parent` dependency management at `26.7.0`, provided Keycloak/Jackson dependencies, JUnit 5 and Mockito test dependencies, pinned Surefire, Enforcer rules for Java/Maven, Spotless or formatter verification, CycloneDX generation, reproducible timestamps, and Maven Wrapper distribution checksum validation. Add Apache-2.0, the approved NOTICE attribution, editor settings, and ignores for `target/`, IDE files, generated SBOM/checksum artifacts, `.env`, and `secrets/` while retaining `*.env.example`.

- [ ] **Step 2: Write the core tests before production classes**

Port and rename the existing fixed-vector and error-path tests into the neutral package, then add assertions for the approved differences:

```java
assertEquals(expectedBase64, AidEncryptedGenerator.generate(
        "123456789", "test-secret", fixedInstant,
        Duration.ofSeconds(300), fixedIv));
assertThrows(IOException.class, () -> CaptchaSecrets.load(mode0640));
assertThrows(IOException.class, () -> CaptchaSecrets.load(mode0400InWritableParent));
assertEquals(new CaptchaVerificationResult(false, "proof-too-large", null),
        verifier.verify("x".repeat(8193), "rand", "203.0.113.9"));
assertEquals(new CaptchaVerificationResult(false, "proof-too-large", null),
        verifier.verify("ticket", "x".repeat(1025), "203.0.113.9"));
```

Tests must use only synthetic values such as `AKIDEXAMPLE`, `SECRETKEYEXAMPLE`, `example.invalid`, and documentation IP ranges.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
./mvnw -B -Dtest=AidEncryptedGeneratorTest,CaptchaSecretsTest,Tc3SignerTest,TencentCaptchaVerifierTest test
```

Expected: compilation fails only because the five production types do not yet exist.

- [ ] **Step 4: Implement the minimal verification core**

Implement the exact approved protocols: AES-256-CBC/PKCS5Padding with cyclic UTF-8 key derivation and `IV || ciphertext` standard Base64; TC3-HMAC-SHA256; exact four-key secret parsing with no-follow metadata; fixed Tencent endpoint/action/version; 3-second connect and 5-second request timeouts; length gates of 8192/1024/255; `CaptchaCode == 1` as the only success. Keep all implementation classes package-private unless Keycloak requires public visibility later.

- [ ] **Step 5: Verify GREEN and repository hygiene**

Run:

```bash
./mvnw -B test
./mvnw -B verify
git diff --check
rg -n -i '(private\.example|synthetic-internal/|AKID[A-Z0-9]{16,}|secret[-_ ]?key\s*=\s*[^<])' . --glob '!docs/superpowers/**'
```

Expected: all Java tests pass, verify exits zero, diff check is clean, and the hygiene scan reports no internal identifier or non-fixture credential.

- [ ] **Step 6: Commit Task 1**

```bash
git add .editorconfig .gitignore .mvn mvnw mvnw.cmd pom.xml LICENSE NOTICE src/main/java src/test/java
git commit -m "feat: add Tencent CAPTCHA verification core"
```

---

### Task 2: Keycloak Authenticator and embedded browser experience

**Files:**
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaAuthenticatorFactoryTest.java`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaAuthenticatorTest.java`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/PackagedResourcesTest.java`
- Create: `src/test/js/tencent-captcha-client.test.mjs`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaAuthenticator.java`
- Create: `src/main/java/io/github/invokerbot/keycloak/tencentcaptcha/TencentCaptchaAuthenticatorFactory.java`
- Create: `src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
- Create: `src/main/resources/theme-resources/templates/tencent-captcha.ftl`
- Create: `src/main/resources/theme-resources/resources/js/tencent-captcha-client.mjs`
- Create: `src/main/resources/theme-resources/resources/css/tencent-captcha.css`
- Create: `src/main/resources/theme-resources/messages/messages_en.properties`
- Create: `src/main/resources/theme-resources/messages/messages_zh_CN.properties`
- Create: `package.json`
- Create: `package-lock.json`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: Task 1 core types and the stable provider/template contract from the design.
- Produces: discoverable Keycloak provider ID `tencent-captcha`, embedded theme resources, dependency-free browser module, and a release JAR usable by Task 3.

- [ ] **Step 1: Write factory, authenticator, packaging, and browser tests**

Tests must assert:

```java
assertEquals("tencent-captcha", factory.getId());
assertArrayEquals(new Requirement[] {Requirement.REQUIRED}, factory.getRequirementChoices());
assertFalse(factory.isConfigurable());
assertFalse(factory.isUserSetupAllowed());
assertEquals(Path.of("/custom/secret"), resolvePath(property=" /custom/secret ", env="/ignored"));
assertJarResource("theme-resources/templates/tencent-captcha.ftl");
assertJarResource("theme-resources/resources/js/tencent-captcha-client.mjs");
```

Authenticator tests cover lazy secret loading, challenge attributes, accepted proof, every rejection path, 503 configuration challenge, CSP nonce and exact origin, forbidden CSP sources, request-scoped header skip, and redacted correlation/log categories. Browser tests use `node:test` with a minimal in-memory DOM double and cover exact URL, nonce, single-flight, cancel/error/empty proof, constructor options exactly `{ aidEncrypted }`, exact action URL, and ticket/randstr-only submission.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./mvnw -B -Dtest=TencentCaptchaAuthenticatorFactoryTest,TencentCaptchaAuthenticatorTest,PackagedResourcesTest test
npm test
```

Expected: Java fails because provider/resources are absent; npm fails because package/browser source is absent.

- [ ] **Step 3: Implement the provider and generic embedded resources**

Implement a stateless Authenticator and Factory under the neutral package. Use the exact script URL and request-scoped CSP algorithm. Render a generic FreeMarker page that imports Keycloak's standard layout, applies local CSS, emits only nonce-bearing inline/module scripts, calls the dependency-free client module, and posts only proof fields to `url.loginAction`. Provide complete English and Chinese messages for action, retry, unavailable, and rejected states.

The ServiceLoader file contains exactly:

```text
io.github.invokerbot.keycloak.tencentcaptcha.TencentCaptchaAuthenticatorFactory
```

- [ ] **Step 4: Verify GREEN, JAR contents, and dependency surface**

Run:

```bash
npm ci
npm test
./mvnw -B verify
jar tf target/keycloak-tencent-captcha-authenticator-0.1.0.jar | sort
./mvnw -B dependency:tree
```

Expected: tests pass; the JAR contains provider classes, ServiceLoader, template, JS, CSS, and both message bundles; it does not contain Keycloak, Jackson, Tencent SDK, test libraries, or secrets.

- [ ] **Step 5: Commit Task 2**

```bash
git add pom.xml package.json package-lock.json src/main src/test
git commit -m "feat: add drop-in Keycloak authenticator"
```

---

### Task 3: Fresh-Keycloak acceptance harness and Docker Compose example

**Files:**
- Create: `tests/integration/Dockerfile`
- Create: `tests/integration/compose.yaml`
- Create: `tests/integration/configure_and_verify.py`
- Create: `tests/integration/run.py`
- Create: `tests/integration/secrets/tencent-captcha.env.example`
- Create: `examples/docker-compose/Dockerfile`
- Create: `examples/docker-compose/compose.yaml`
- Create: `examples/docker-compose/prepare-secret.sh`
- Create: `examples/docker-compose/secrets/tencent-captcha.env.example`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/ExampleFilesTest.java`
- Modify: `.gitignore`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: Task 2 JAR and stable provider/resource contracts.
- Produces: `tests/integration/run.py` as the cross-platform, bounded CI acceptance entry point and a user-facing Linux Compose example for Task 4 docs.

- [ ] **Step 1: Write failing structural tests for examples and integration fixtures**

Assert that Compose uses a read-only bind mount to `/run/secrets/tencent-captcha.env`, never uses the four CAPTCHA keys, `env_file`, or secret-bearing image build arguments, pins Keycloak `26.7.0`, and binds ports to loopback. Non-production `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD` environment values are permitted only in the disposable integration stack and must use explicit synthetic literals. Assert `prepare-secret.sh` creates mode-0700 directory, owner UID 1000/GID 0, mode-0400 file, refuses symlinks, and validates exact placeholder keys.

- [ ] **Step 2: Run structural tests and verify RED**

Run:

```bash
./mvnw -B -Dtest=ExampleFilesTest test
```

Expected: failure because integration/example files do not exist.

- [ ] **Step 3: Implement the acceptance harness and example**

The integration Dockerfile copies the built JAR before `kc.sh build`. The Compose stack uses synthetic bootstrap credentials and local-only ports. `configure_and_verify.py` uses Python standard-library HTTP APIs to obtain an admin token, create a disposable realm/client, copy Browser Flow, add provider `tencent-captcha` inside Forms before Username Password Form as REQUIRED, bind it, and output only structural booleans/status codes.

`run.py` uses `subprocess.run(..., timeout=...)`, bounded HTTP polling, and `try/finally` cleanup, then emits and verifies each independent structural result:

```text
ready=true
providerDiscovered=true
embeddedTemplate=true
browserFlow=tencent-captcha-test-browser
captchaRequirement=REQUIRED
missingSecretFailClosed=true
invalidSecretFailClosed=true
challengeCspExact=true
scriptUrlExact=true
constructorOptionsAidEncryptedOnly=true
loginHeadersUnchanged=true
registrationHeadersUnchanged=true
errorHeadersUnchanged=true
masterHeadersUnchanged=true
accountConsoleHeadersUnchanged=true
```

No test submits a proof to Tencent. Unit tests cover Tencent response behavior; the container test stops at rendering and missing/invalid-secret failure.

- [ ] **Step 4: Verify examples and full container acceptance**

Run through the runner's built-in 10-minute process bound:

```bash
./mvnw -B verify
python3 tests/integration/run.py
docker compose -f examples/docker-compose/compose.yaml config
```

Expected: all structural and container checks pass, cleanup removes containers/networks, and no secret value appears in output on either macOS or Linux controllers.

- [ ] **Step 5: Commit Task 3**

```bash
git add .gitignore pom.xml src/test/java tests/integration examples/docker-compose
git commit -m "test: add fresh Keycloak acceptance stack"
```

---

### Task 4: Public documentation and community files

**Files:**
- Create: `README.md`
- Create: `README.zh-CN.md`
- Create: `docs/configuration.md`
- Create: `docs/authentication-flow.md`
- Create: `docs/security-model.md`
- Create: `docs/compatibility.md`
- Create: `SECURITY.md`
- Create: `CONTRIBUTING.md`
- Create: `CODE_OF_CONDUCT.md`
- Create: `CHANGELOG.md`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`
- Create: `.github/pull_request_template.md`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/DocumentationTest.java`

**Interfaces:**
- Consumes: actual commands, paths, names, and verified behavior from Tasks 1-3.
- Produces: complete English/Chinese operator guidance and community/security policy for publication.

- [ ] **Step 1: Write documentation contract tests**

Tests check both READMEs for exact GAV, Keycloak/Java matrix, JAR and Docker installation, `kc.sh build`, flow placement, secret schema/modes, proxy/IP, CSP, fail-closed, privacy, non-official notice, upgrade/rollback/uninstall/troubleshooting, and links to focused docs. They reject production domains, source-deployment names, `private.example` paths, unredacted `SecretKey=` examples, and instructions that put secrets in environment/Realm exports.

- [ ] **Step 2: Run documentation tests and verify RED**

Run:

```bash
./mvnw -B -Dtest=DocumentationTest test
```

Expected: failure because public docs/community files are absent.

- [ ] **Step 3: Write public documentation and templates**

Write concise, equivalent English and Chinese setup paths. All examples use `<CAPTCHA_APP_ID>`, `<CAPTCHA_APP_SECRET_KEY>`, `<TENCENT_SECRET_ID>`, and `<TENCENT_SECRET_KEY>` placeholders. Document data sent to Tencent, trusted-proxy requirements, exact protected/unprotected flows, security-reporting address, support window for `0.1.x`, contribution verification commands, Apache-2.0 contributions, and a changelog entry for `0.1.0`.

- [ ] **Step 4: Verify documentation and links**

Run:

```bash
./mvnw -B verify
rg -n 'TODO|TBD|private\.example|synthetic-internal/' README* docs SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md CHANGELOG.md .github || true
```

Expected: tests pass and the scan returns no placeholder work or internal identifiers.

- [ ] **Step 5: Commit Task 4**

```bash
git add README.md README.zh-CN.md docs SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md CHANGELOG.md .github/ISSUE_TEMPLATE .github/pull_request_template.md src/test/java
git commit -m "docs: add public installation and security guides"
```

---

### Task 5: CI, security scanning, and release automation

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/codeql.yml`
- Create: `.github/workflows/secret-scan.yml`
- Create: `.github/workflows/release.yml`
- Create: `.github/dependabot.yml`
- Create: `scripts/release_artifacts.py`
- Create: `scripts/verify_release_artifacts.py`
- Create: `scripts/wait_for_workflows.py`
- Create: `scripts/tests/test_release_artifacts.py`
- Create: `scripts/tests/test_wait_for_workflows.py`
- Create: `src/test/java/io/github/invokerbot/keycloak/tencentcaptcha/WorkflowPolicyTest.java`
- Modify: `pom.xml`
- Modify: `CONTRIBUTING.md`

**Interfaces:**
- Consumes: verification commands and artifact paths from Tasks 1-4.
- Produces: required GitHub checks and tag-triggered JAR/SHA256/SBOM/attestation release workflow for Task 6.

- [ ] **Step 1: Write workflow policy tests before workflows**

Tests parse workflow text and require top-level least-privilege permissions, immutable 40-character action SHAs, Java 21, Node 20, Keycloak container acceptance, Maven cache keyed by lock/build files, advanced CodeQL for Java/JavaScript, Gitleaks on PR/full history, tag-only release, verification before packaging, SHA256SUMS, CycloneDX SBOM, and `actions/attest-build-provenance`. Reject `pull_request_target`, write permissions in PR workflows, floating action tags, secrets in command lines, and Docker image publication. Python unit tests use temporary directories and injected `gh` JSON results to prove exact artifact-set/checksum/SBOM validation and SHA-aware bounded workflow polling, including empty, stale, failed, and timed-out results.

- [ ] **Step 2: Run workflow tests and verify RED**

Run:

```bash
./mvnw -B -Dtest=WorkflowPolicyTest test
python3 -m unittest discover -s scripts/tests
```

Expected: failures because workflow and Python implementation files do not exist.

- [ ] **Step 3: Implement pinned workflows and artifact script**

Use official actions where available, pinned to reviewed full commit SHAs. `ci.yml` runs `npm ci && npm test`, `./mvnw -B verify`, and `python3 tests/integration/run.py`. `codeql.yml` is advanced setup. `secret-scan.yml` runs Gitleaks. `release.yml` triggers only on `v*`, verifies tag/version equality, reruns all gates, builds the thin JAR, calls the cross-platform Python artifact scripts, uploads assets, creates a GitHub Release, and attests the JAR/checksum/SBOM subjects. `wait_for_workflows.py` queries `gh run list --json` repeatedly, matches exact workflow names and `headSha`, applies a caller-provided timeout, and fails on missing, stale, timed-out, or non-success runs.

- [ ] **Step 4: Validate workflows and release artifacts locally**

Run:

```bash
./mvnw -B verify
npm ci && npm test
python3 -m unittest discover -s scripts/tests
python3 scripts/release_artifacts.py 0.1.0
python3 scripts/verify_release_artifacts.py dist
git diff --check
```

If `actionlint` is available, run it; otherwise run it through a pinned container. The verifier parses the CycloneDX JSON, requires the exact approved asset set and non-zero sizes, and checks every SHA-256 entry with Python `hashlib`. Expected: all tests pass, workflow lint passes, artifact verification passes, and `dist/` contains only the approved JAR, SHA256SUMS, and CycloneDX JSON.

- [ ] **Step 5: Commit Task 5**

```bash
git add .github/workflows .github/dependabot.yml scripts pom.xml CONTRIBUTING.md src/test/java
git commit -m "ci: add verified release supply chain"
```

---

### Task 6: Final audit, GitHub publication, and `v0.1.0`

**Files:**
- Verify: entire repository and Git history
- Modify only if review finds an in-scope defect

**Interfaces:**
- Consumes: completed and reviewed Tasks 1-5.
- Produces: public GitHub repository, protected `main`, enabled security features, and a verified `v0.1.0` release.

- [ ] **Step 1: Run complete local verification with fresh evidence**

Run:

```bash
npm ci && npm test
./mvnw -B clean verify
python3 tests/integration/run.py
python3 scripts/release_artifacts.py 0.1.0
python3 scripts/verify_release_artifacts.py dist
git diff --check
git status --short --branch
```

Expected: all tests and container gates pass; only ignored/generated `dist/` may be present; tracked tree is clean before publication.

- [ ] **Step 2: Run secret, internal-identifier, license, and dependency audit**

Run Gitleaks against the complete new Git history and working tree, inspect `git log -p --all`, scan for internal domains/paths and high-entropy fixtures, inspect JAR contents, run `./mvnw dependency:tree`, verify all source files have the agreed SPDX header where configured, and confirm README/license/NOTICE consistency. No finding may be waived without a written rationale and reviewer approval.

- [ ] **Step 3: Perform final two-stage and cross-module reviews**

Dispatch a spec-compliance reviewer, then a code-quality/security reviewer, then a final cross-module reviewer over Java, resources, examples, docs, workflows, and release artifacts. Resolve all Critical and Important findings and rerun affected tests before continuing.

- [ ] **Step 4: Create and configure the public repository**

With authenticated `gh` account `invoker-bot`, first confirm the target does not exist, then create it without generating files:

```bash
gh repo create invoker-bot/keycloak-tencent-captcha-authenticator \
  --public --source=. --remote=origin \
  --description "Fail-closed Tencent Cloud CAPTCHA Authenticator for Keycloak" \
  --push
```

Set homepage/topics, enable vulnerability alerts, automated security fixes, secret scanning, and push protection through supported `gh api` endpoints. Configure `main` protection after the initial CI run exposes exact check names: require those checks, one approving review for future pull requests, conversation resolution, no force pushes, and no deletions. Do not weaken settings if an API feature is unavailable; report the exact limitation.

- [ ] **Step 5: Verify remote CI before release**

Resolve the pushed commit SHA, then use a bounded Python/`gh` poller that filters every workflow by `headSha` and waits for exactly `ci.yml`, `codeql.yml`, and `secret-scan.yml` to reach `completed/success`. It must reject an empty result, a workflow attached to another commit, timeout after 20 minutes, or any non-success conclusion.

Run:

```bash
remote_main_sha="$(git rev-parse HEAD)"
python3 scripts/wait_for_workflows.py \
  --repo invoker-bot/keycloak-tencent-captcha-authenticator \
  --sha "$remote_main_sha" --timeout 1200 \
  --workflow ci.yml --workflow codeql.yml --workflow secret-scan.yml
gh repo view invoker-bot/keycloak-tencent-captcha-authenticator --json nameWithOwner,visibility,defaultBranchRef,url
```

Expected: all required initial workflows pass on the public `main` commit and repository metadata is exact.

- [ ] **Step 6: Publish and verify `v0.1.0`**

Create an annotated `v0.1.0` tag only after remote CI passes, push the tag, and use the same bounded SHA-aware poller for the release workflow. Download the published assets into a new restricted temporary directory, verify the exact asset set, non-zero sizes, checksum file and CycloneDX JSON with the cross-platform verifier, then verify provenance with `gh attestation verify` without exposing secrets:

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
tag_sha="$(git rev-list -n 1 v0.1.0)"
python3 scripts/wait_for_workflows.py \
  --repo invoker-bot/keycloak-tencent-captcha-authenticator \
  --sha "$tag_sha" --timeout 1200 --workflow release.yml
gh release view v0.1.0 --json url,assets,isDraft,isPrerelease
release_tmp="$(mktemp -d)"
chmod 700 "$release_tmp"
gh release download v0.1.0 --dir "$release_tmp"
python3 scripts/verify_release_artifacts.py "$release_tmp"
gh attestation verify "$release_tmp/keycloak-tencent-captcha-authenticator-0.1.0.jar" \
  --repo invoker-bot/keycloak-tencent-captcha-authenticator
rm -rf "$release_tmp"
```

Expected: a non-draft, non-prerelease `v0.1.0` release with the approved three asset classes and a successful provenance attestation.

- [ ] **Step 7: Final handoff**

Report the local commit, public repository URL, release URL, exact verification commands/results, compatibility scope, security settings, and any non-blocking follow-up. Do not update the production deployment to consume the release in this task.
