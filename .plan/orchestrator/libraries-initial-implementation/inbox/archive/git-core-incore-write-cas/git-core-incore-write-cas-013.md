envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:26:17Z

component=plan-marshall:plan-marshall
category=bug
confidence=high
source=plan-retrospective

# Forward --step-id on every record-dispatch-boundary call

## Context

In plan git-core-incore-write-cas every dispatch-boundary row (4-plan 1, 5-execute 4, 6-finalize 6, 11 in total) was written without a step key. The execution-context dispatch audit's firing comparison therefore paired 0 of 11 boundary rows against the 19 execution_log rows, and reported every execution row (verify:quality-gate, verify:module-tests, automatic-review, create-pr, ...) as unpaired.

## Root cause

The orchestrator call sites in the plan-marshall workflows (execution.md after an execute envelope returns, and the finalize dispatcher after each step) omit `--step-id` on `manage-metrics record-dispatch-boundary`. The flag is optional, and an omitted flag silently writes an empty key, so nothing fails.

## Proposed action

Make every orchestrator `record-dispatch-boundary` invocation forward the dispatch's `record-step` step key (`--step-id {step_id}`; for execute envelopes the envelope or verify step key). Consider a warning in `record-dispatch-boundary` when a phase that has execution_log rows receives a keyless row, so the gap is visible at write time instead of at retrospective time.

## Evidence

- aspect: execution-context-dispatch-audit — firing_comparison keyless_boundary_rows 1/4/6, paired_firings 0 in every phase
- aspect: logging-gap-analysis — DISPATCH_TERMINATION_CAUSE gap, all rows keyless
