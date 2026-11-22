envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:57:59Z

component=plan-marshall:build-maven
category=bug

# Write a change-ledger build row for each maven wrapper run

Source: plan-retrospective of plan-09-store-release-index-listeners (log-analysis and plan-efficiency aspects).

## Context

The script log shows 50 `plan-marshall:build-maven:maven` calls, 3.24M ms in total and 42% of all script time. The change-ledger was present and readable (16 rows scanned), but held no build row for this plan, so the retrospective reports build time as unavailable and cannot reconcile the build count.

## Root cause

The rows this plan wrote were deliverable commit rows. The maven wrapper runs did not append build rows, or appended them where the retrospective does not read them (the worktree-local ledger, removed with the worktree).

## Proposed action

Check that the maven wrapper appends a build row (command, status, duration) for each run, and that the ledger rows written in a worktree are carried into the main checkout when the plan integrates, so build time survives worktree removal.

## Evidence

- aspect: log_analysis - build_time: ledger_rows_scanned 16, summed_rows 0, total_build_seconds unavailable; log_build_calls 50
- aspect: plan_efficiency - total_build_seconds unavailable
