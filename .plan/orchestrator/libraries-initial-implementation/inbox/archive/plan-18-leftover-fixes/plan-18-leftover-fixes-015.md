envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:28:12Z

component=plan-marshall:plan-marshall
category=bug
title=Forward --step-id on every record-dispatch-boundary call

# Forward --step-id on every record-dispatch-boundary call

## Context

All 24 dispatch-boundary rows of plan-18-leftover-fixes (1 in 4-plan, 5 in 5-execute, 18 in 6-finalize) were written without a `--step-id`. The retrospective's firing comparison therefore paired 0 of the 32 `execution_log` rows (8 in 5-execute, 24 in 6-finalize), and the ledger reconciliation had to fall back to timestamp windows.

## Root cause

The orchestrator call sites of `manage-metrics record-dispatch-boundary` do not forward the dispatch's step key, although the verb documents that it must be forwarded on every call that has a step.

## Proposed action

Pass `--step-id {step_id}` (the same key `record-step` writes) at every orchestrator dispatch-boundary call site in the execute and finalize dispatch loops, and add a check that flags a keyless boundary row in a phase whose execution log carries step ids.

## Evidence

- aspect: execution_context_dispatch_audit - firing_comparison keyless_boundary_rows 1/5/18, paired_firings 0 in every phase
- aspect: logging_gap_analysis - DISPATCH_TERMINATION_CAUSE gap, 24/24 rows keyless
