envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:16:25Z

# Record worked duration for a re-entered 5-execute from its dispatch boundaries

Component: plan-marshall:manage-metrics. Category: bug. Confidence: high. Source: plan-retrospective.

## Context

In plan-23-forms-hardening, `metrics.md` shows 5-execute with Worked `-`, Reported (wall) 3h19m and Idle 3h19m: the whole execute window is graded as idle. The same phase has 6 dispatch-boundary rows whose `duration_ms` sum to 8,541,329 ms (about 2h22m), and its `total_tokens` (273,114) is far below the dispatch-boundary total (2,048,047) that won the reconciliation. The phase was re-entered (3 closes: two budget_yield envelopes, a verification hand-back, and a finalize loop-back for TASK-19).

## Root cause

Worked duration for 5-execute is neither accumulated per dispatch nor backfilled from the boundary rows on a re-entered phase, so the idle residual absorbs all of the agent time, while tokens are reconciled from the boundary rows. The two columns use different sources for the same dispatches.

## Proposed action

Backfill a re-entered phase's worked duration from its dispatch-boundary `duration_ms` (as tokens already are), or mark Idle as unmeasured when Worked is unrecorded, so execute effort is not reported as 3h19m of idle time.

## Evidence

- aspect: plan_efficiency - 5-execute worked unrecorded, idle equals wall; boundary duration_ms sum 8541329
- aspect: log_analysis - dispatch_boundaries 5-execute 6 rows with duration_ms 583713 to 2151705
