#!/usr/bin/env python3
"""Require a release tag to point at current main and its successful CI gates."""

from __future__ import annotations

import argparse
import re
import subprocess
from collections.abc import Callable

from wait_for_workflows import wait_for_workflows

SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
REQUIRED_WORKFLOWS = ["ci.yml", "codeql.yml", "secret-scan.yml"]


class ReleaseRefError(RuntimeError):
    """The checked-out release ref is not the current, verified main commit."""


def _git(command: list[str], runner: Callable[..., subprocess.CompletedProcess[str]]) -> str:
    try:
        completed = runner(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseRefError("git-command-unavailable-or-timed-out") from None
    if completed.returncode != 0:
        raise ReleaseRefError("git-command-failed")
    return completed.stdout.strip()


def verify_release_ref(
    repo: str,
    *,
    timeout: float,
    poll_interval: float = 10.0,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    waiter: Callable[..., object] = wait_for_workflows,
) -> str:
    """Return the exact release commit after current-main and CI verification."""
    if timeout <= 0 or poll_interval <= 0:
        raise ValueError("timeout and poll interval must be positive")
    _git(
        ["git", "fetch", "--no-tags", "origin", "+refs/heads/main:refs/remotes/origin/main"],
        runner,
    )
    release_commit = _git(["git", "rev-parse", "HEAD^{commit}"], runner)
    current_main = _git(["git", "rev-parse", "refs/remotes/origin/main"], runner)
    if SHA_PATTERN.fullmatch(release_commit) is None or SHA_PATTERN.fullmatch(current_main) is None:
        raise ReleaseRefError("git-returned-invalid-commit")
    if release_commit != current_main:
        raise ReleaseRefError("tag-commit-is-not-current-main")
    waiter(
        repo,
        release_commit,
        REQUIRED_WORKFLOWS,
        timeout=timeout,
        poll_interval=poll_interval,
    )
    return release_commit


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--timeout", type=float, default=1200)
    parser.add_argument("--poll-interval", type=float, default=10)
    args = parser.parse_args()
    try:
        commit = verify_release_ref(
            args.repo,
            timeout=args.timeout,
            poll_interval=args.poll_interval,
        )
    except (ReleaseRefError, ValueError) as error:
        parser.exit(1, f"verify-release-ref: {error}\n")
    print(f"release-ref-verified={commit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
