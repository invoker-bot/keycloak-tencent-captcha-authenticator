#!/usr/bin/env python3
"""Wait for exact GitHub workflow files at an exact commit SHA using bounded gh calls."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from collections.abc import Callable, Sequence
from typing import Any

SHA_PATTERN = re.compile(r"[0-9a-fA-F]{40}")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
WORKFLOW_PATTERN = re.compile(r"[A-Za-z0-9_.-]+\.ya?ml")
GH_JSON_FIELDS = "headSha,status,conclusion,databaseId"


class WorkflowWaitError(RuntimeError):
    """A workflow run is absent, stale, unsuccessful, or did not finish in time."""


def _query_runs(
    repo: str,
    workflow: str,
    *,
    timeout: float,
    runner: Callable[..., subprocess.CompletedProcess[str]],
) -> list[dict[str, Any]]:
    command = [
        "gh",
        "run",
        "list",
        "--repo",
        repo,
        "--workflow",
        workflow,
        "--limit",
        "100",
        "--json",
        GH_JSON_FIELDS,
    ]
    try:
        completed = runner(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=max(0.1, min(30.0, timeout)),
        )
    except (OSError, subprocess.TimeoutExpired):
        raise WorkflowWaitError(f"gh run list unavailable or timed out for workflow {workflow}") from None
    if completed.returncode != 0:
        raise WorkflowWaitError(f"gh run list failed for workflow {workflow}")
    try:
        runs = json.loads(completed.stdout)
    except (TypeError, json.JSONDecodeError):
        raise WorkflowWaitError(f"gh run list returned invalid JSON for workflow {workflow}") from None
    if not isinstance(runs, list) or any(not isinstance(run, dict) for run in runs):
        raise WorkflowWaitError(f"gh run list returned an invalid result shape for workflow {workflow}")
    return runs


def _validate_arguments(repo: str, sha: str, workflows: Sequence[str], timeout: float, poll_interval: float) -> None:
    if REPOSITORY_PATTERN.fullmatch(repo) is None:
        raise ValueError("repo must use owner/name format")
    if SHA_PATTERN.fullmatch(sha) is None:
        raise ValueError("sha must be an exact 40-character commit SHA")
    if not workflows or len(set(workflows)) != len(workflows):
        raise ValueError("at least one unique workflow is required")
    if any(WORKFLOW_PATTERN.fullmatch(workflow) is None for workflow in workflows):
        raise ValueError("workflow values must be exact .yml or .yaml file names")
    if timeout <= 0 or poll_interval <= 0:
        raise ValueError("timeout and poll interval must be positive")


def wait_for_workflows(
    repo: str,
    sha: str,
    workflows: Sequence[str],
    *,
    timeout: float,
    poll_interval: float = 10.0,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, dict[str, Any]]:
    """Return successful exact-SHA runs, or raise a bounded redacted error."""
    _validate_arguments(repo, sha, workflows, timeout, poll_interval)
    deadline = monotonic() + timeout
    completed_runs: dict[str, dict[str, Any]] = {}
    saw_any = {workflow: False for workflow in workflows}
    saw_stale = {workflow: False for workflow in workflows}
    saw_pending = {workflow: False for workflow in workflows}

    while monotonic() < deadline:
        for workflow in workflows:
            if workflow in completed_runs:
                continue
            remaining = deadline - monotonic()
            if remaining <= 0:
                break
            runs = _query_runs(repo, workflow, timeout=remaining, runner=runner)
            saw_any[workflow] = saw_any[workflow] or bool(runs)
            exact = [run for run in runs if run.get("headSha") == sha]
            if not exact:
                saw_stale[workflow] = saw_stale[workflow] or bool(runs)
                continue
            selected = max(exact, key=lambda run: int(run.get("databaseId", 0)))
            if selected.get("status") != "completed":
                saw_pending[workflow] = True
                continue
            conclusion = selected.get("conclusion")
            if conclusion != "success":
                raise WorkflowWaitError(f"workflow {workflow} concluded with {conclusion or 'unknown'}")
            completed_runs[workflow] = selected
        if len(completed_runs) == len(workflows):
            return completed_runs
        remaining = deadline - monotonic()
        if remaining > 0:
            sleep(min(poll_interval, remaining))

    unresolved = [workflow for workflow in workflows if workflow not in completed_runs]
    pending = [workflow for workflow in unresolved if saw_pending[workflow]]
    stale = [workflow for workflow in unresolved if saw_stale[workflow] and not saw_pending[workflow]]
    missing = [workflow for workflow in unresolved if not saw_any[workflow]]
    if pending:
        raise WorkflowWaitError(f"timed out waiting for workflow: {','.join(pending)}")
    if stale:
        raise WorkflowWaitError(f"no run for requested head SHA: {','.join(stale)}")
    raise WorkflowWaitError(f"no workflow run found: {','.join(missing or unresolved)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--workflow", action="append", dest="workflows", required=True)
    parser.add_argument("--timeout", type=float, required=True)
    parser.add_argument("--poll-interval", type=float, default=10.0)
    args = parser.parse_args()
    try:
        runs = wait_for_workflows(
            args.repo,
            args.sha,
            args.workflows,
            timeout=args.timeout,
            poll_interval=args.poll_interval,
        )
    except (ValueError, WorkflowWaitError) as error:
        parser.exit(1, f"wait-for-workflows: {error}\n")
    for workflow in args.workflows:
        print(f"workflow-success={workflow} run-id={runs[workflow].get('databaseId', 'unknown')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
