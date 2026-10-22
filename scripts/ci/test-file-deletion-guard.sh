#!/usr/bin/env bash
# Fails a PR diff that deletes a test file without a DELETE_TESTS: <reason>
# line in one of the PR's commit messages.
#
# Usage: test-file-deletion-guard.sh [BASE_REF] [HEAD_REF]
#   BASE_REF defaults to $BASE_SHA, then origin/main.
#   HEAD_REF defaults to $HEAD_SHA, then HEAD.
# Exit 0 = no violation. Exit 1 = test file(s) deleted without justification.
set -euo pipefail

BASE_REF="${1:-${BASE_SHA:-origin/main}}"
HEAD_REF="${2:-${HEAD_SHA:-HEAD}}"

# Matches files under a test(s)/__tests__ directory, or named *.test.*,
# *.spec.*, test_*, *_test.*, or *Test.<ext>/*Spec.<ext>.
TEST_FILE_PATTERN='(^|/)(tests?|__tests__)/|(^|/)[^/]+\.(test|spec)\.[^/]+$|(^|/)test_[^/]+$|(^|/)[^/]+_test\.[^/]+$|(^|/)[^/]+(Test|Spec)\.[A-Za-z0-9]+$'

deleted_files="$(git diff --name-status "$BASE_REF" "$HEAD_REF" -- | awk '$1 == "D" {print $2}')"

deleted_test_files="$(printf '%s\n' "$deleted_files" | grep -E "$TEST_FILE_PATTERN" || true)"

if [ -z "$deleted_test_files" ]; then
  echo "test-file-deletion-guard: no test files deleted. OK."
  exit 0
fi

echo "test-file-deletion-guard: deleted test file(s) detected:"
echo "$deleted_test_files" | sed 's/^/  - /'

commit_messages="$(git log --format=%B "$BASE_REF".."$HEAD_REF")"
justification="$(printf '%s\n' "$commit_messages" | grep -E '^DELETE_TESTS: *\S' | head -1 || true)"

if [ -n "$justification" ]; then
  echo "test-file-deletion-guard: justified by commit message: \"$justification\""
  exit 0
fi

echo "test-file-deletion-guard: FAIL — test file(s) were deleted but no commit" >&2
echo "in this PR has a 'DELETE_TESTS: <reason>' line. Add one, or restore the file(s)." >&2
exit 1
