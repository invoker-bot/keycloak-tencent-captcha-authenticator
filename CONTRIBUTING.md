# Contributing

Thank you for helping improve this unofficial Keycloak extension.

## Before opening a change

- Search existing issues and pull requests.
- Use a feature request for contract or behavior proposals before a large implementation.
- Use [GitHub private vulnerability reporting](SECURITY.md) for suspected security issues; never disclose them in a public issue.
- Keep the provider independent of application-specific code, private infrastructure, a particular secret store, and third-party themes.

## Development environment

- Java 21 or newer (the artifact targets Java 21)
- Maven Wrapper committed in this repository
- Node.js 20 or newer
- Python 3 for integration harness unit tests
- Linux and Docker Compose only for the fresh-Keycloak container acceptance suite

Build dependencies are resolved by Maven and npm. Do not add credentials to Maven configuration, npm configuration, source files, fixtures, issue text, or test output.

## Test-first workflow

For behavior changes, add a focused failing test, run it and confirm the expected RED reason, make the smallest implementation, then run the focused test and complete suite to GREEN. Keep production code, browser behavior, documentation, and acceptance fixtures consistent.

Run the local verification set:

```bash
./mvnw -B verify
npm test
python3 -m unittest discover -s tests/integration -p 'test_*.py'
python3 -m unittest discover -s scripts/tests
```

On a Linux host with Docker available, build first and then run the bounded fresh-container acceptance suite:

```bash
./mvnw -B verify
python3 tests/integration/run.py
python3 tests/integration/run_public_example.py
```

Both runners are bounded and always attempt cleanup. The public-example runner refuses to start when its target secret path already exists (including a symlink), invokes the shipped secret helper, verifies its Linux metadata contract, and removes only its generated secret. They use synthetic fixtures and must never be given live Tencent credentials. Live Tencent verification is an operator acceptance activity, not a pull-request test.

Before submitting, run `git diff --check` and scan public files for unfinished markers, internal hostnames, private infrastructure identifiers, and secret-store paths. The expected scan output is empty.

## CI and release supply chain

Pull requests and pushes to `main` run the same Java, Node.js, fresh-Keycloak acceptance, CodeQL, and full-history Gitleaks gates. GitHub Actions are pinned to reviewed immutable commit SHAs; do not replace them with floating tags. The secret scan installs a fixed Gitleaks CLI release only after verifying its reviewed official SHA-256, then explicitly scans `git --log-opts=--all`; do not replace it with an action's implicit scan range. Pull-request workflows keep top-level permissions read-only, the CodeQL PR job grants only `contents: read` and `security-events: write` so it can upload results, and this project never uses `pull_request_target`.

To test the release asset contract without creating a tag or remote release, run:

```bash
./mvnw -B verify
python3 scripts/release_artifacts.py 0.1.0
python3 scripts/verify_release_artifacts.py dist
```

The generated `dist/` directory must contain only the thin provider JAR, `SHA256SUMS`, and the versioned CycloneDX JSON SBOM. The tag-only release workflow requires exact `v<project.version>` equality, reruns every acceptance gate, attests all three release subjects, and creates a GitHub Release. It does not build or publish a container image.

Before any attestation or publication, the release workflow runs `scripts/verify_release_ref.py`. That behavioral gate fetches current `origin/main`, requires the checked-out tag commit to equal it exactly, and uses the bounded exact-SHA workflow waiter to require successful `ci.yml`, `codeql.yml`, and `secret-scan.yml` runs for that same commit.

After an authorized push, maintainers can wait for an exact commit and exact workflow files without exposing `gh` command output:

```bash
python3 scripts/wait_for_workflows.py \
  --repo invoker-bot/keycloak-tencent-captcha-authenticator \
  --sha <40-character-commit-sha> --timeout 1200 \
  --workflow ci.yml --workflow codeql.yml --workflow secret-scan.yml
```

## Pull requests

- Keep one coherent change per pull request and explain its security and compatibility impact.
- Add or update tests and operator documentation for every public behavior change.
- Preserve provider ID `tencent-captcha` and the documented `0.1.x` contract unless the proposal explicitly introduces a breaking release.
- Use only synthetic angle-bracket placeholders in public examples: `<CAPTCHA_APP_ID>`, `<CAPTCHA_APP_SECRET_KEY>`, `<TENCENT_SECRET_ID>`, and `<TENCENT_SECRET_KEY>`.
- Do not log or attach proof fields, credentials, raw responses, session identifiers, client IPs, production names, or Realm exports.
- Confirm that ordinary Keycloak pages retain their security headers and that failures remain fail-closed.

## License

The project is licensed under Apache-2.0 (Apache License 2.0). By submitting a contribution, you agree that your contribution is provided under the same license and that you have the right to submit it.

Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
