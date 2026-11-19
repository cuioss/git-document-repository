envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:26:54Z

component=plan-marshall:phase-5-execute
category=bug
confidence=high
source=plan-retrospective

# Fix the documented change-ledger append flag --changed-paths-file

## Context

In plan git-core-incore-write-cas two separate phase-5 execute envelopes (the envelope-1 resume running TASK-2 to TASK-5, and the review-fix envelope running TASK-010/011) each reported that the Step 10a `manage-change-ledger append --changed-paths-file` call was rejected as an unknown flag. Both retried with the comma-separated `--changed-paths` and logged the rejection. The plan's script-failure analysis did not list this rejection among its three argparse findings, so the lessons-capture step (messages 010-012) did not carry it either.

## Root cause

The phase-5 workflow documents a flag the `manage-change-ledger append` parser does not declare (or the worktree's installed script lags the documentation). Every deliverable commit pays one failed call plus a retry.

## Proposed action

Align the Step 10a invocation with the `append` parser: either document `--changed-paths` (comma-separated) or add `--changed-paths-file` to the parser, and add the invocation to the canonical-invocations guard so the drift fails a test. Also check why script-failure-analysis did not classify this rejection.

## Evidence

- aspect: chat-history-analysis — envelope-1-resume return ("Step 10a ... --changed-paths-file was rejected as an unknown flag") and TASK-010/011 return ("the worktree's script runner rejects the --changed-paths-file flag")
- aspect: log-analysis — errors_work 5 against 3 classified script failures
