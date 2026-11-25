envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:27:57Z

component=plan-marshall:execution-context
category=bug
signal=retrospective-audit
shape=actionable

# Leaf builds and wait-for-comments calls are moved to the background by the harness after about 180 s

## What happened

In plan-17-timestamp-rfc3161 several dispatched leaves reported that the harness moved their long Bash calls to the background: execute envelopes 1 and 3 ("builds over 180 s were moved to the background"), and automatic-review passes for `ci pr wait-for-comments` (about 196-198 s). The parent then received interim task notifications saying the agent "is waiting on its own background work" before the real hand-back. This is the result-bearing backgrounding inside a leaf that the execution-context rules forbid. It suggests those calls did not carry the explicit Bash `timeout` (600000 ms ceiling) the envelope requires, or that a workflow-chosen wait (for example a 180 s review-bot poll plus overhead) exceeds the default foreground limit.

## Corrective action

- Make the build-wrapper and `ci pr wait-for-comments` invocation blocks in the workflow docs state the Bash `timeout` parameter explicitly (resolved through `harness bash-timeout-ceiling`), not only the wrapper's own `--timeout`.
- Add a retrospective check that flags `run_in_background` or harness auto-backgrounding of result-bearing calls inside a leaf.

## Impact

Lost synchronous return paths, interim duplicate notifications to the orchestrator, and a known-lossy wait pattern inside leaves that should never use it.
