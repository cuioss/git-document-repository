envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:36Z

Candidate lesson (retrospective: token measurements of this plan are floors)

component=plan-marshall:manage-metrics
category=improvement
title=Record the token columns on every dispatch-boundary row

## Context

All 33 dispatch-boundary rows (4-plan 1, 5-execute 12, 6-finalize 20) record `total_tokens` but leave input, output, cache-read and cache-creation columns unmeasured, so `context_position_cost` is unmeasured for every phase. 1-init tokens are `unknown`, 6-finalize is unclosed at retrospective time, and 5-execute's two measures disagree (dispatch-boundary sum 4,656,764 vs enrich total 2,840,587). The dispatcher also reports some figures were recorded from interim usage values. The plan's 10.9M-token total is therefore a floor and its per-phase split approximate.

## Root cause

The boundary recorder is called with the aggregate total only; the per-column usage the dispatch return carries is not forwarded, and the main-context init phase has no measurement source.

## Proposed action

Pass the per-column usage figures to `record-dispatch-boundary` whenever the dispatch return carries them, record which figures were interim, and give 1-init a main-context measurement so the totals stop being n=5/6.

## Evidence

- aspect: log_analysis - context_position_cost measured_rows 0 of 33
- aspect: logging_gap_analysis - token columns unmeasured on every row
- aspect: plan_efficiency - totals_tokens_population_count 5/6, 5-execute measures disagree by 1.8M
