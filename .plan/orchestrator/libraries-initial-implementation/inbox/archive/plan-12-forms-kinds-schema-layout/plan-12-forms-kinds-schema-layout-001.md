envelope_version=1
sender_type=plan
sender_id=plan-12-forms-kinds-schema-layout
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T21:34:51Z

component=plan-marshall:phase-6-finalize
category=bug
title=Refuse pre-push-quality-gate done until the build verdict at HEAD is green
confidence=high
source_plan=plan-12-forms-kinds-schema-layout

# Refuse pre-push-quality-gate done until the build verdict at HEAD is green

## Context

On the final round of PR 54 the finalize steps `finalize-step-simplify` and `pre-push-quality-gate` were logged
`Completed (outcome=done)` at 20:00:47Z and 20:00:48Z, the same second the build in
`build-results/default/maven-2026-11-22-195931.log` finished with BUILD FAILURE (git-store,
`ConcurrentWritersTest.tenWritersWithOwnClones`, "Failed to close extension context"). The branch was pushed on that
verdict. A re-run at 20:01:08Z on the identical tree was green, so no broken code landed, but the gate passed on a red
build. Neither re-fired step logged an `Executing step` line, so the step log alone does not show the order.

## Root cause

The step was closed by a chained command that did not wait for and read the build TOON `status` before marking the step
done; nothing in `mark-step-done` ties the gate's outcome to the recorded build result.

## Proposed action

Make `mark-step-done --step pre-push-quality-gate --outcome done` check the change-ledger's last build row for the
current HEAD and refuse unless it is `status: success` (an explicit operator override aside). Have the step always log
its `Executing step` line on re-fire. This is distinct from the flaky-test lesson 2026-11-22-21-001: that one is about
the test, this one is about the gate accepting a red verdict.

## Evidence

- aspect: logging_gap_analysis - re-fired pre-push-quality-gate and simplify log Completed with no Executing line
- aspect: llm_to_script_opportunities - gate marked done in the same second the red build finished
- build log maven-2026-11-22-195931.log, lines 800-814: 1 error in 406 tests, BUILD FAILURE, finished 21:00:47+01:00
