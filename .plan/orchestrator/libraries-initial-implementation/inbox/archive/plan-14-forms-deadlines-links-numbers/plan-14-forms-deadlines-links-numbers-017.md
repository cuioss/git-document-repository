envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:31Z

# Check recorded step heads before dispatching branch-cleanup

- Source: plan-retrospective (logging-gap-analysis, log-analysis dispatch boundaries, chat-history-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-6-finalize
- Category: improvement
- Confidence: high

## Context

The first branch-cleanup dispatch (6-finalize dispatch-boundary row at 2026-11-27T19:42:26Z, termination_cause=error) spent 235,283 tokens and 124 s, then stopped at `pre_merge_gate merge` with `merge_candidate_not_gated`: finalize-step-simplify had recorded head 66f30e9 while the PR head was 5b439be (one 2-line commit later). The dispatcher then re-fired simplify (40K tokens) and re-dispatched branch-cleanup, which merged.

## Root cause

The HEAD-binding check runs only inside the branch-cleanup leaf, after rebase routing, CI snapshot, review barrier, ADR scan and mutex acquisition. The comparison it fails on (each gated step's head_at_completion vs the candidate head) is known to the dispatcher before the dispatch.

## Proposed action

Run the same head-binding comparison in the phase-6-finalize dispatcher before handing off branch-cleanup, and re-fire any lagging gated step first. A refusal that the dispatcher can predict should not cost a full dispatch.

## Evidence

- log-analysis: 6-finalize error_total_tokens=235283, one `error` row
- decision.log 2026-11-27T19:41:45Z: "Pre-merge HEAD binding: merge_candidate_not_gated ... ungated steps finalize-step-simplify"
