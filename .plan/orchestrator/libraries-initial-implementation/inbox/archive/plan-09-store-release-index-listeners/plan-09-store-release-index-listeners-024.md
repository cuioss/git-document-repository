envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:57:58Z

component=plan-marshall:manage-metrics
category=improvement

# Record step_id and token sub-columns on every dispatch-boundary row

Source: plan-retrospective of plan-09-store-release-index-listeners (execution-context-dispatch-audit and log-analysis aspects).

## Context

All 22 dispatch-boundary rows (4-plan 1, 5-execute 7, 6-finalize 14) carry no step_id and leave input, output and cache token columns unmeasured. The firing comparison therefore paired 0 of 34 execution-log rows, context-position cost is unmeasured on every row, and finalize channel confidence is low (6 dispatch lines against 20 completions). With three re-fires per finalize step in this plan, the per-step cost of each re-fire cannot be read off the record.

## Root cause

The `record-dispatch-boundary` call sites pass only the total tokens and the termination cause, not the step key or the usage breakdown that the leaf's `<usage>` block carries.

## Proposed action

Have the finalize and execute dispatchers pass `step_id` (finalize step or envelope id) and the token sub-columns when they record a boundary, and emit the matching `[DISPATCH]` line for every finalize dispatch.

## Evidence

- aspect: execution-context-dispatch-audit - firing_comparison paired_firings 0 in all 3 phases; keyless_boundary_rows 1/7/14; channel confidence low (ratio 0.3)
- aspect: log_analysis - context_position_cost measured_rows 0 of 22
