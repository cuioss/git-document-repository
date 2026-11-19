envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:17:07Z

# Stamp step_id and token columns on every dispatch-boundary row

- Component: plan-marshall:manage-metrics
- Category: improvement
- Source: plan-retrospective (plan-03-git-worktrees-import), confidence high

## Context

All 13 dispatch-boundary rows of this plan (1 in 4-plan, 4 in 5-execute, 8 in 6-finalize) carry no `step_id` and
list `input_tokens`, `output_tokens`, `cache_read_input_tokens` and `cache_creation_input_tokens` as unmeasured. The
retrospective's firing comparison therefore paired 0 boundary rows with the 20 `execution_log` rows in every phase,
and the context-position cost was unmeasured for all 13 rows.

## Root cause

`record-dispatch-boundary` is called without the step key and without the per-class token breakdown, so the rows
can be summed but not attributed to the step that fired them.

## Proposed action

Pass the step id (the same key `execution_log` uses) and the token breakdown from the `<usage>` record on every
`record-dispatch-boundary` call, and have the recorder refuse or warn on a keyless row.

## Evidence

- execution_context_dispatch_audit: keyless_boundary_rows 1/4/8, paired_firings 0 in 4-plan, 5-execute, 6-finalize
- log_analysis: context_position_cost measured_rows 0 of 13
- logging_gap_analysis: DISPATCH_TERMINATION_CAUSE rows present but unattributable
