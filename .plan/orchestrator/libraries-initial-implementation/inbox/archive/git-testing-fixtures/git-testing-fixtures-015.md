envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:57:51Z

# Candidate lesson: every dispatch-boundary row was written without --step-id, so the ledgers cannot be paired

- Source signal: plan-retrospective, execution-context-dispatch-audit and logging-gap aspects
- Evidence: `firing_comparison` — 4-plan 1 boundary row / 0 execution rows, 5-execute 6 / 7, 6-finalize 9 / 18; keyless_boundary_rows = 16 of 16; paired_firings = 0 in every phase. All 16 rows also carry the four context-load columns as `unmeasured`.

## What happened

The orchestrator called `manage-metrics record-dispatch-boundary` after each phase dispatch, but never passed `--step-id`. The execute-log rows (`verify:module-tests`, `verify:quality-gate`, `automatic-review`, `pre-push-quality-gate`, ...) do carry their step ids. With one side keyless, the reconciliation had no join key, and the timestamp-window fallback paired nothing either. As a result 25 execution-log rows are reported unpaired, and the per-dispatch context-load cost (`context_position_cost`) has 0 measured rows across a 3.9M-token plan.

## Candidate rule

The dispatch sites in plan-marshall/workflow/execution.md and phase-6-finalize that call `record-dispatch-boundary` should forward the dispatch's step key (`--step-id`) and the four context-load figures on every call, since the manage-metrics contract states an omitted `--step-id` is correct only when the dispatch has no step. A run whose boundary rows are all keyless should be flagged as an instrumentation gap, not left as a silent zero pairing.

Classification is deferred to the orchestrator.
