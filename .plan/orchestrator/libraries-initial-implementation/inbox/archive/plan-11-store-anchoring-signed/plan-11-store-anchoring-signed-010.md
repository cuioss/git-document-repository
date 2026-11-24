envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:36:02Z

# Candidate lesson: Keep result-bearing builds synchronous when they exceed the harness auto-background limit

component=plan-marshall:phase-5-execute category=bug confidence=high source=plan-retrospective

## Context

In PLAN-11 the execute envelopes ran `verify -Ppre-commit` per task. That gate takes about 290 s here (the slowest maven call in the script log was 350 s). Envelope 1 reported that "the harness moves any command running longer than 180 s to the background, so every gate run finished in the background and came back by notification". Envelope 2 then ended four times with "waiting on its own background work" before it handed back. One gate run also stopped at the learned build timeout of 326 s and had to be re-run with `--timeout 590`.

## Root cause

The leaf contract says a dispatched leaf never backgrounds result-bearing work and must pass an explicit Bash timeout up to the 600000 ms ceiling. In practice, on this target, a build over about 180 s was still moved to the background, so the leaf waited on its own background task. The learned build timeout (326 s) was also below the real gate duration.

## Proposed action

Check whether the Bash `timeout` the execute leaf passes really reaches the tool call for canonical build commands (and whether the harness honours it above 180 s). If the harness cannot keep these builds synchronous, route long per-task gates to the orchestrator-tier execution path instead of the leaf. Seed the adaptive build timeout from the observed gate duration, not from a smaller learned value.

## Evidence

- aspect: chat_history_analysis - envelope 1 note on 180 s auto-background; envelope 2 "waiting on its own background work" x4
- aspect: log_analysis - plan-marshall:build-maven:maven 54 calls, max 350330 ms, 47% of script time
