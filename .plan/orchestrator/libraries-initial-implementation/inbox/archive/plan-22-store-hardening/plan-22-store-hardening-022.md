envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:41Z

Candidate lesson (retrospective: squash message cannot be set on the merge-queue path)

component=plan-marshall:phase-6-finalize
category=improvement
title=Give branch-cleanup a way to set the squash message on the merge queue

## Context

branch-cleanup landed PR #76 through the merge queue (squash). Its § Squash-message composition asks, on the queue path, to note the landing limitation in a PR comment and to set the repository's default merge-commit message through the `ci repo` surface. That surface has no verb for the message, so the leaf did neither half, and c22700ad carries the repository default squash message, which may include PR body text such as the CodeRabbit summary.

## Root cause

The workflow prescribes an action the CI abstraction does not expose.

## Proposed action

Add a `ci repo` verb for the default squash/merge message (or a per-PR merge-message input on the queue route), or rewrite the workflow section to the action that is actually available.

## Evidence

- aspect: chat_history_analysis - branch-cleanup hand-back "One documented step was not done ... That surface has no verb for setting the message"
