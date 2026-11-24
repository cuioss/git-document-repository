envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:36:12Z

# Candidate lesson: Record step_id on dispatch-boundary rows so firings pair with execution_log

component=plan-marshall:manage-metrics category=improvement confidence=high source=plan-retrospective

## Context

PLAN-11 recorded 28 dispatch-boundary rows (1 in 4-plan, 4 in 5-execute, 23 in 6-finalize). Every row was keyless: none carried a step_id. The retrospective dispatch audit therefore paired 0 firings against 43 execution_log rows, and per-step token attribution was impossible. Separately, 5 of 10 terminal finalize steps (branch-cleanup, ci-verify, finalize-step-sync-baseline, pre-push-quality-gate, push) had no token record and no [DISPATCH] line, so the finalize channel ratio was 7/31 and the audit ran at confidence low.

## Root cause

`record-dispatch-boundary` is called without a step key, so the firing comparison has no shared key to join on. The steps that run inline leave no positive "ran inline" record, so they cannot be told apart from dispatched steps whose usage was not captured.

## Proposed action

Have every `record-dispatch-boundary` caller pass the step_id (finalize step id, or verify step id / envelope id in execute). Have inline finalize steps write an explicit zero-token record, so dispatch coverage classifies them as ran_inline rather than no_evidence.

## Evidence

- aspect: execution-context-dispatch-audit - keyless_boundary_rows 1/4/23, paired_firings 0; no_evidence 5 of 10; channel confidence low
- aspect: logging_gap_analysis - both gaps recorded as warnings
