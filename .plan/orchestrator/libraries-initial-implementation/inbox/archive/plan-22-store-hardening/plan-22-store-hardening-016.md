envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:30Z

Candidate lesson (retrospective: a paused review bot counted as having reviewed the merge head)

component=plan-marshall:automatic-review
category=bug
title=Treat a paused review bot as not having reviewed the merge head

## Context

PR #76 merged by merge queue as c22700ad at head 09b57182. CodeRabbit, the required bot, had posted "Reviews paused" with "Latest commit, not reviewed: 09b5718"; its last finished review was at c52acb44. automatic-review iteration 8 marked done because `finished_review_bots` named CodeRabbit and it declares `review_complete_after_handling`, and the pre-merge barrier then reported `participation_complete: true`. The delta c52acb44..09b57182 (TASK-23 and TASK-24: a deferred nested index move and a new refusal of snapshot/open inside a clone callback) reached main without any bot review.

## Root cause

The participation guard proves participation on the PR, not review of the merge head, and a bot-side pause is read as a settled review on record rather than as "this head was not reviewed".

## Proposed action

Recognise the bot's paused/not-reviewed notice for the current head as a distinct state (not `finished_review`), and have automatic-review either trigger a resume or surface a prompt-required envelope so the operator decides whether to merge an unreviewed delta. The pre-merge barrier should name the unreviewed head range in its record.

## Evidence

- aspect: chat_history_analysis - iteration-8 hand-back: "Reviews paused", "Latest commit, not reviewed: 09b5718", review on record at c52acb44
- status.json phase_steps automatic-review head_at_completion 09b57182 with display "1 reviewed"
- loop_back_budgets wait-region-unified-triage grant 4 reason: "CodeRabbit auto-paused, so this is the last reviewed round"
