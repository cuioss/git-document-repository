envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:42Z

Candidate lesson (retrospective: foreground builds moved to background despite a 600000 ms timeout)

component=plan-marshall:persona-plan-marshall-agent
category=bug
title=Correct the claim that a 600000 ms timeout keeps a build synchronous

## Context

The persona's Bash-timeout rule says passing the resolved timeout keeps a long build synchronous and avoids the host's auto-move to background. In this plan at least five leaves (TASK-7, TASK-8, TASK-11, automatic-review iteration 5, branch-cleanup) reported that the harness moved foreground calls started with `timeout: 600000` to the background after about 180 s (quality gates, `pr wait-for-comments`, `bot_completion`, `wait-for-queue-settle`, `checks wait`). Each waited for the completion notice rather than polling, so no result was lost, but the documented guarantee did not hold.

## Root cause

The host's auto-background threshold is independent of the per-call timeout, and the standard states the opposite.

## Proposed action

Update the timeout rule (and the platform-runtime bash-timeout-ceiling contract) to state the observed behaviour and the sanctioned response: wait for the completion notification, never poll, never re-issue the call.

## Evidence

- aspect: chat_history_analysis - "The harness moved both gate builds to the background after 180 s, even though they were started in the foreground with a 600000 ms timeout"; repeated in four later hand-backs
- aspect: logging_gap_analysis - VOLUNTARY_CHECKPOINT_POLLING 0 (the leaves did not poll)
