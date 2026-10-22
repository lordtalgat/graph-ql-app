# CI gates

## `test-file-deletion-guard`

Workflow: [`.github/workflows/test-file-deletion-guard.yml`](../.github/workflows/test-file-deletion-guard.yml)
Check logic: [`scripts/ci/test-file-deletion-guard.sh`](../scripts/ci/test-file-deletion-guard.sh)

### What it enforces

The gate scans a pull request's diff for deleted test files (any path under a `test(s)`/`__tests__` directory, or named `*.test.*`, `*.spec.*`, `test_*`, `*_test.*`, `*Test.<ext>`, or `*Spec.<ext>`). If any are deleted, it requires at least one commit in the PR to contain a `DELETE_TESTS: <reason>` line; otherwise the check fails.

### Why it exists for AI-assisted PRs

An agent asked to "make the build green" or "fix this failing test" has a cheap, always-available way to satisfy that goal: delete the test. Deleting a test file is also a one-line diff that's easy to miss in review next to substantive code changes, and an agent has no instinct that stops it the way a human author's discomfort usually would. This gate doesn't block deletion — it blocks *silent* deletion, by requiring the deleter to say why in the commit message, where it's visible in `git log` and in the PR's commit list.

### What it does NOT catch

- A test file that's gutted (its body replaced with a trivial `assert(true)`, or its assertions weakened/removed) rather than deleted. The file still exists, so this check sees no violation.
- A test removed by renaming/moving it to a non-test-looking path, or restructured so it no longer matches the test-file patterns above.
- Whether the stated `DELETE_TESTS: <reason>` is actually a good reason — the check only verifies the line exists, not that it's true or sufficient. That judgment is left to the human reviewer.

### One limitation

Pattern matching happens on file *path*, not file content — a non-test file that happens to match a test-ish name (e.g. `src/test-fixtures/data.json`) would be flagged as a false positive, and conversely a real test file in an unconventional location that matches none of the patterns would be missed entirely.

## Admin step: making the check required

Execute this yourself once you have write/admin access to the repository's branch protection settings (an AI session should not change branch protection without being asked to — it's a shared-state, hard-to-reverse change).

**GitHub:** repo → **Settings** → **Branches** → edit (or add) the protection rule for `main` → under **Require status checks to pass before merging**, enable it and add `test-file-deletion-guard` to the required-checks list → **Save changes**.

**GitLab (equivalent concept):** repo → **Settings** → **Merge requests** → **Merge checks**, combined with making the pipeline job itself required via **Settings → CI/CD → General pipelines** (`allow_failure: false` on the job) — GitLab doesn't have a separate "required status checks" list the way GitHub does; a non-allow-failure job in a required pipeline is the equivalent.

### Rollout checklist

| Step | UI path | Who can do this | Done |
|---|---|---|---|
| Merge this workflow + script into `main` | PR review/merge | Any repo maintainer | ☐ |
| Confirm the check appears on a real PR | Open any PR against `main`, check the checks list | Any contributor | ☐ |
| Make it required | GitHub: Settings → Branches → `main` → Require status checks → add `test-file-deletion-guard` | Repo admin | ☐ |
| Announce it to the team | Wherever PR conventions are communicated (README, team channel) | Repo admin / maintainer | ☐ |
