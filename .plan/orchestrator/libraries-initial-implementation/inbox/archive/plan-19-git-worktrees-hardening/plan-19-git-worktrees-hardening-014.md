envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:57:49Z

# Candidate lesson: change-ledger holds no build-duration row for a plan that ran 36 maven builds

- Source: plan-retrospective (log-analysis build_time block, plan-efficiency)
- Suggested component: plan-marshall:manage-change-ledger (or the build-maven wrapper that should append build rows)
- Suggested category: bug
- Confidence: medium (one plan; cause not traced)

## Context

The retrospective's build-time oracle reads build rows from the change-ledger. For this plan the ledger was present and readable with 14 rows, but `summed_rows: 0` and `build_count: 0`, so `total_build_seconds` is `unavailable`. The script log shows 36 `plan-marshall:build-maven:maven` calls (1,719,700 ms cumulative, 31% of script time), and the phase-5 freshness gate reported `basis=ledger-verified, matched_notation=plan-marshall:build-maven:maven` twice.

## Root cause

Not traced. Either maven builds append no build row of the shape `summarize_build_ledger` counts, or they append rows without `duration_seconds`. The freshness gate evidently finds something it accepts, so the two readers may disagree on the row shape.

## Proposed action

Check which ledger rows the freshness gate matched and why `summarize_build_ledger` counted none of them; make maven build rows carry a duration so build time is measured for Maven plans.

## Evidence

- log_analysis.build_time: ledger_present true, ledger_readable true, ledger_rows_scanned 14, summed_rows 0, build_count 0.
- build_count_reconciliation: log_build_calls 36, ledger_available false.
- work log 21:22:11Z and 21:58:31Z: "Worktree freshness: basis=ledger-verified".
