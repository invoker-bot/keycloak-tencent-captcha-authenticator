## Summary

Describe the problem, the focused change, and the operator-visible result.

## Contract and risk

- Public `0.1.x` contract affected:
- Authentication-flow impact (including SSO Cookie, registration, reset, and direct grant):
- Fail-closed, CSP, proxy/client-IP, privacy, and secret-handling impact:
- Upgrade, rollback, and uninstall impact:

## Verification

- [ ] I wrote or updated a focused test and observed the expected RED result before implementation.
- [ ] `./mvnw -B verify`
- [ ] `npm test`
- [ ] `python3 -m unittest discover -s tests/integration -p 'test_*.py'`
- [ ] Linux fresh-container acceptance, or an explanation of why it was not run
- [ ] `git diff --check`
- [ ] Public link and internal-identifier scan is empty

## Safety checklist

- [ ] No credentials, proof values, authorization data, raw provider responses, client IPs, user data, production names, or Realm exports are included.
- [ ] Public examples use only angle-bracket placeholders.
- [ ] Documentation and changelog are updated for public behavior.
- [ ] The change is portable and does not depend on a private application, infrastructure service, secret store, or external theme.
- [ ] This pull request does not disclose a vulnerability; security reports use private vulnerability reporting.

## Reviewer notes

Call out any area that requires compatibility, security, or operator-documentation review.
