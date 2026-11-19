envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:33:42Z

# Maven builds leave no build row in the change-ledger, so plan build time is never measured

**Source signal:** retrospective log-analysis `build_time` block and logging-gap analysis

**Component:** plan-marshall:build-maven (maven run wrapper), with plan-marshall:manage-change-ledger as the receiving store

**Category:** bug

## Context

The plan logged 45 `plan-marshall:build-maven:maven` calls in `script-execution.log`, about 2,189 s in total and 52% of all script time. The change-ledger was present and readable and held 14 rows for the plan, but none of them was a build row (`summed_rows: 0`). So `build_time.total_build_seconds` is `unavailable`, and the build-count reconciliation reports the ledger as unmeasured for this plan.

## Root cause

The maven run wrapper (or the call site that is supposed to record its outcome) writes no `kind=build` ledger row. The build-time oracle the retrospective reads therefore stays empty, even on a plan whose cost was mostly builds.

## Proposed action

Have the build-maven `run` verb append one ledger build row per invocation (command, duration_seconds, status), the same way the other build systems do. Alternatively, document which caller owns that append and fix that caller. Then add a check that a plan with logged build calls and zero ledger build rows produces a finding.

## Evidence

- aspect: log_analysis: `log_build_calls: 45`, `build_time.summed_rows: 0`, `ledger_rows_scanned: 14`, `ledger_unavailable_reason: the change-ledger holds no row for this plan`
- aspect: logging_gap_analysis: BUILD_LEDGER gap
- aspect: plan_efficiency: `total_build_seconds: unavailable`
