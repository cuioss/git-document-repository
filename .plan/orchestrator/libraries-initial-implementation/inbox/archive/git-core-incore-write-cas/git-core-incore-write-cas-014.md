envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:26:31Z

component=plan-marshall:manage-metrics
category=improvement
confidence=high
source=plan-retrospective

# Derive the closing phase token delta from boundary rows, not a typed flag

## Context

In plan git-core-incore-write-cas one 5-execute close was recorded with `--total-tokens 743` where 742996 was intended (742996 = the envelope-2 and envelope-3 dispatch-boundary rows, 365142 + 377854). Because explicit flags on a re-entered row are additive deltas and there is no subtract or correct verb, the wrong value could not be repaired. The row now holds total_tokens 941972 against a dispatch-boundary sum of 1329933. The rendered Total is unaffected only because the reconciliation picked the larger boundary measure, which masks the typo rather than catching it. The same row was also closed twice ten seconds apart (09:31:55 and 09:32:05) with no intervening work.

## Root cause

The orchestrator transcribes per-dispatch usage figures by hand into `phase-boundary` / `end-phase --total-tokens`, although the same figures are already on disk in `work/metrics-dispatch-boundaries-{phase}.toon`. A hand-copied figure has no plausibility check and no correction path.

## Proposed action

When `--total-tokens` / `--tool-uses` / `--duration-ms` are omitted on `end-phase` or `phase-boundary`, derive the closing delta from the dispatch-boundary rows recorded since the previous close (as the accumulator fallback already does for finalize), and make the orchestrator workflows omit the flags. Optionally warn when an explicit figure differs from the boundary-row delta by more than an order of magnitude, and offer an explicit correction verb for a re-entered row.

## Evidence

- aspect: plan-efficiency — 5-execute total_tokens 941972 vs dispatch_boundary_total 1329933, tokens_cell_source dispatch_boundary_total
- aspect: llm-to-script-opportunities — hand-summed usage figures are a scripting candidate
- work/metrics.toon phase_intervals — three 5-execute closes, two of them 10 seconds apart
