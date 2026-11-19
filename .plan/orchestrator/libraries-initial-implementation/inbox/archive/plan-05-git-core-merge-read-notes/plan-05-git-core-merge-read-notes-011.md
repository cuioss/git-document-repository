envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:33:56Z

# Dispatch-boundary rows carry no step_id, so finalize dispatches cannot be paired with their steps

**Source signal:** retrospective execution-context dispatch audit (`firing_comparison`, `dispatch_coverage`, `channel_completeness`)

**Component:** plan-marshall:manage-metrics (`record-dispatch-boundary`) and its phase-5 / phase-6 call sites

**Category:** improvement

## Context

All 18 dispatch-boundary rows the plan recorded are keyless: 1 in 4-plan, 6 in 5-execute and 11 in 6-finalize. The firing comparison therefore paired 0 boundary rows with the 20 `execution_log` rows, and reported every row as unpaired. Of the 10 terminal finalize steps, 8 have no token or dispatch evidence: automatic-review, branch-cleanup, ci-verify, create-pr, sync-baseline, pre-push-quality-gate, push and sonar-roundtrip. Channel confidence is `low`: 6 finalize dispatch lines against 20 completions. In addition, every boundary row lists the four token-split columns as unmeasured.

## Root cause

`record-dispatch-boundary` is called without the step id of the dispatch it closes, or does not persist one. The retrospective has no shared key to join a boundary row (spend and termination cause) to the finalize step or verify step that caused it.

## Proposed action

Add a `--step-id` argument to `record-dispatch-boundary`, persist it, and pass it from every phase-5 verify dispatch and phase-6 step dispatch site. Then `check-dispatch-audit` can pair firings and attribute the finalize spend per step: 1.54M tokens of boundary total, the largest phase.

## Evidence

- aspect: execution_context_dispatch_audit: `keyless_boundary_rows` 1/6/11, `paired_firings: 0` in every phase, `no_evidence: 8 of 10`, `confidence: low`
- aspect: logging_gap_analysis: DISPATCH_FIRING_KEY and DISPATCH_CHANNEL gaps
- aspect: log_analysis: `context_position_cost.measured_rows: 0 of 18`
