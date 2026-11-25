envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:38Z

Candidate lesson (retrospective: PR re-delivery left a stale create-pr record)

component=plan-marshall:automatic-review
category=bug
title=Re-bind the create-pr step record when recovery re-opens the PR

## Context

On a CodeRabbit quota refusal, automatic-review's rate-limit recovery returned `close_and_reopen`: it closed PR #75 and opened PR #76 on the same branch, and re-bound `pr_number` in the plan references to 76. The status record `phase_steps.6-finalize.create-pr` still says display "#75" with `facts.pr_number: "75"`, so the plan's own step history names a closed PR as the one it created and merged.

## Root cause

The re-delivery branch updates references but not the create-pr step record, and nothing reconciles the two after the swap.

## Proposed action

When recovery re-opens the PR, also amend the create-pr step facts (or append a superseding fact naming old and new PR numbers), so later readers and the landing record resolve the merged PR from status alone.

## Evidence

- aspect: chat_history_analysis - hand-back "pr_number_changed: true, old_pr_number 75, new_pr_number 76"
- status.json phase_steps create-pr facts pr_number "75"; branch-cleanup merged PR 76 as c22700ad
