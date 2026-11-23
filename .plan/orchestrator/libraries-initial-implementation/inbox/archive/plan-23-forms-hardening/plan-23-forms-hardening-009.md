envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:42Z

# Candidate lesson: leaf agents backgrounded result-bearing builds twice

Signal: leaf-rule violation observed in the logs (no script failure marker; orchestrator-reported, verified by timing).

## What happened

Twice in this run, the build-busy title token was set and then cleared within about 2 seconds, and only after that was the in-process build resolved and started. The Bash call returned at once while the build was still running:

- 09:20:43 token set, 09:20:45 cleared, 09:20:46 build resolved. The `maven run` finished at 09:26:01 after 316 s (phase-5 envelope 2, around TASK-012/013).
- 10:14:08 token set, 10:14:09 cleared, 10:14:10 build resolved (pre-push-quality-gate leaf).

The execution-context contract allows `run_in_background` in a leaf only for fire-and-forget work. A build whose verdict the next step needs must run synchronously with an explicit timeout (600000 ms here). No wrong verdict resulted, but the backgrounding hides the exit code from the synchronous path and makes the build-busy bracket wrong.

## Candidate rule

Leaves keep backgrounding long builds despite the rule. The likely trigger is a build expected to run longer than the default timeout. The canonical build commands in CLAUDE.md and the phase-5 / pre-push workflow text should state the explicit `timeout: 600000` at the call site. The build-hook token clear that comes before the build-resolution line could also serve as a detector: a cleared token with no completed `maven run` yet means the build was backgrounded.

Scope: plan-marshall execution-context leaf discipline (phase-5-execute, pre-push-quality-gate).
