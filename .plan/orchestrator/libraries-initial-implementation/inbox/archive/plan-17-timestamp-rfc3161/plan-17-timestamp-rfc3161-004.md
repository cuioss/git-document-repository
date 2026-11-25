envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:30Z

component=plan-marshall:tools-file-ops
category=improvement
signal=script-failure
shape=actionable

# Interrupted atomic file write after a session restart leaves a temp file and a half-applied edit

## What happened

In plan-17-timestamp-rfc3161, a session restart interrupted an atomic file write. The worktree was left with a `*.tmp.*` file next to the target and an edit that was only half applied. Recovery was manual: re-apply the helper, then delete the temp file by hand. The exact helper is not recorded in this message; the orchestrator should match it against the run's logs.

## Corrective action

- On resume, scan the worktree for orphaned `*.tmp.*` files that the atomic-write helper creates, and report them as a resume finding instead of leaving them for the build or a commit to stumble over.
- Make multi-file edits idempotent and resumable, so re-running the helper converges on the intended state.
- Tie temp-file cleanup to the helper itself (try/finally, or cleanup at the next start), not only to the success path.

## Impact

Manual recovery, and a risk of committing a stray temp file or a half-applied edit.
