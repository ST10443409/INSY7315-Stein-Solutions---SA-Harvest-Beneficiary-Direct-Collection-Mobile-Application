#!/usr/bin/env bash
# Prints "true" when the event that started this workflow changed a file matching the extended regex in $1, else "false".
#
# Why this exists instead of `paths:` in the workflow triggers: branch protection makes a check "required", and a workflow
# that a path filter stops from starting never reports that check, so the pull request waits for it forever. Here the
# workflow always starts, a cheap `changes` job calls this script, and the expensive job is skipped with `if:` when
# nothing relevant changed. GitHub counts a skipped job as passed, so protection works and Android-only changes still do
# not run the backend pipeline (and the other way round).
#
# Reads: EVENT, PR_BASE, PR_HEAD (pull_request) or PUSH_BEFORE, PUSH_AFTER (push). Needs a full-history checkout.
# Anything it cannot work out (manual run, first push of a branch, a force push whose old tip is gone) answers "true":
# running a pipeline that was not needed is a smaller mistake than skipping one that was.
set -euo pipefail

pattern="${1:?usage: changed-paths.sh '<extended regex of relevant paths>'}"
zeros=0000000000000000000000000000000000000000

case "${EVENT:-}" in
  pull_request) range="${PR_BASE:?}...${PR_HEAD:?}" ;;
  push)
    if [ -z "${PUSH_BEFORE:-}" ] || [ "${PUSH_BEFORE}" = "$zeros" ]; then echo true; exit 0; fi
    range="${PUSH_BEFORE}..${PUSH_AFTER:?}"
    ;;
  *) echo true; exit 0 ;;
esac

if ! files="$(git diff --name-only "$range" 2>/dev/null)"; then echo true; exit 0; fi

if printf '%s\n' "$files" | grep -Eq "$pattern"; then echo true; else echo false; fi
