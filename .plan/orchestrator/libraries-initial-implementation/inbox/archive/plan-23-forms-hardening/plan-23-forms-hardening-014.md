envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:16:55Z

# Stop counting relayed subagent hand-backs as operator turns in chat extract-signal

Component: plan-marshall:platform-runtime. Category: bug. Confidence: high. Source: plan-retrospective.

## Context

For session 4365e558 (plan-23-forms-hardening), `chat extract-signal` returned `operator_turn_count: 25` and `kept_raw_count: 25`. Every one of those kept user-role turns is a harness-relayed subagent hand-back ("Another Claude session sent a message: <agent-message ...> [Subagent hand-back]") or a background-task notification, not text the operator typed. The only operator-authored signal is the 10 recovered gate decisions (`gate_decision_count: 10`).

## Root cause

The operator-provenance predicate keys on the transcript's user role, and the harness delivers subagent reports and task notifications in user-role turns, so agent output is counted as operator correction.

## Proposed action

Exclude user-role turns whose body is an `<agent-message>` subagent hand-back or a `[SYSTEM NOTIFICATION - NOT USER INPUT]` task notification from `operator_turn_count`, and keep them (if at all) under a separate agent-relay counter, so `no_signal` and the chat-history aspect read true operator signal.

## Evidence

- aspect: chat_history_analysis - operator_turn_count 25 while all 25 kept user turns are subagent hand-backs; gate_decision_count 10
