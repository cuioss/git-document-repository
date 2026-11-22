envelope_version=1
sender_type=plan
sender_id=plan-12-forms-kinds-schema-layout
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T21:35:05Z

component=plan-marshall:manage-metrics
category=bug
title=Reject placeholder dispatch-boundary rows and flag disagreeing token measures
confidence=high
source_plan=plan-12-forms-kinds-schema-layout

# Reject placeholder dispatch-boundary rows and flag disagreeing token measures

## Context

The 5-execute dispatch-boundary file of plan-12 holds a row `2026-11-22T19:43:13Z clean_exit_queue_empty 0 0 0`,
recorded 12 seconds before the real row for the same envelope (335287 tokens, 140 tool uses). The dispatcher also
reported an execute row first written as an estimate and corrected later; the append-only file leaves no trace of that
correction, so it cannot be audited. Separately, the two measures of the 5-execute phase disagree by a factor of 1.9:
`total_tokens` summed over 7 closes is 1756491, the boundary sum over 11 rows is 3368153, and the report takes the
larger one with a note. The plan total (9325248) therefore rests on a cell whose true value is not decidable from the
artifacts. All 43 boundary rows also leave input, output and cache tokens unmeasured, and billing is measured on 0 of
6 phases.

## Root cause

`record-dispatch-boundary` accepts an all-zero row as a measurement and gives no way to supersede a wrong row, and the
reconciler resolves a large disagreement silently by taking the maximum instead of surfacing it as a finding.

## Proposed action

- Refuse a row with total_tokens, tool_uses and duration_ms all 0 unless a `--measured-zero` flag says it is real.
- Add a supersede operation that marks a row replaced (keeping both) so a correction is auditable.
- When the competing measures of one phase differ by more than a set ratio (say 1.5x), emit a warning in `metrics.md`
  and in the retrospective, not only the "largest wins" note.

## Evidence

- aspect: plan_efficiency - 5-execute total_tokens 1756491 vs dispatch_boundary_total 3368153; billing population 0/6
- aspect: logging_gap_analysis - zero placeholder row at 19:43:13Z; estimate correction not traceable
- aspect: log_analysis - context_position_cost measured_rows 0 of 43
