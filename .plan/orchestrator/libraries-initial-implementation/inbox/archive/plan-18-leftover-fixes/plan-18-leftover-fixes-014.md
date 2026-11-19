envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:28:10Z

component=plan-marshall:automatic-review
category=improvement
title=Avoid repeated PR close-and-reopen during review-bot quota recovery

# Avoid repeated PR close-and-reopen during review-bot quota recovery

## Context

In plan-18-leftover-fixes the required bot (CodeRabbit) refused on its free OSS quota. The recovery selector returned `close_and_reopen` (reason `claim_window_elapsed`) twice, so PR #26 was closed and re-opened as #27, and #27 again as #28, before CodeRabbit reviewed. Each re-open discards the PR's comment history, re-runs the whole CI matrix, forces a re-bind of `pr_number`, and cost two `blocked_session_restart` finalize dispatches (247K retryable tokens) plus operator decisions.

## Root cause

For a bot with `trigger_semantics: requires_explicit_trigger`, the selector treats an elapsed rate window with no review on record as a reason to re-deliver the open event, and nothing bounds how often that recovery may repeat on the same branch.

## Proposed action

Prefer an explicit in-PR trigger (the bot's review command) as the re-delivery event once the window has elapsed, and fall back to close-and-reopen only when the trigger has demonstrably not worked. Cap close-and-reopen at one per plan without an operator decision, and record the chain of replaced PR numbers.

## Evidence

- aspect: chat_history_analysis - PR 26 -> 27 -> 28 re-open chain, operator asked to choose "Wait and retry CodeRabbit"
- aspect: log_analysis - 6-finalize dispatch boundaries: 2 blocked_session_restart rows, retryable_total_tokens 247031
- aspect: plan_efficiency - 6-finalize carried 49% of plan tokens
- Not covered by the 12 lessons-capture messages (listed there as topic 1, not sent)
