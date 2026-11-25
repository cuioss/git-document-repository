envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:27:55Z

component=plan-marshall:build-maven
category=bug
signal=retrospective-audit
shape=actionable

# The learned build timeout kills green whole-tree verify runs as the test suite grows

## What happened

In plan-17-timestamp-rfc3161 the whole-tree `verify -Ppre-commit` hit the build wrapper's learned timeout at least three times with every test passed and no failure: 365 s in envelope 2 (1978 tests, earlier runs about 297 s), 300 s on TASK-11 in envelope 3 (re-run with `--timeout 560` was green), and 415 s in envelope 4 (2033 passed, 0 failed). Each kill produced no verdict, so the step was recorded `error` and the gate was handed back to the orchestrator or re-run. The suite grew from 1964 to 2195 tests over the plan, and the learned budget trailed the real duration.

## Corrective action

- Add more headroom to the learned timeout (for example the larger of a multiple of the last green duration and the observed maximum plus a margin), and raise it on a timeout whose log shows tests still passing.
- When the wrapper kills a run whose surefire output already shows all tests passed, report it as `timeout` with the pass count rather than as a bare error, so the caller can re-run once with a larger budget instead of handing back.

## Impact

At least three extra whole-tree builds (about 5-7 minutes each) and two verification hand-backs to the orchestrator.
