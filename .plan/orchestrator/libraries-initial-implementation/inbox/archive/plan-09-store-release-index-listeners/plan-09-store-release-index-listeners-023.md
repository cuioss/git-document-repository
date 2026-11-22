envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:57:56Z

component=plan-marshall:platform-runtime
category=bug

# Append the new session id to session_ids when a plan resumes in a new session

Source: plan-retrospective of plan-09-store-release-index-listeners (logging-gap and chat-history aspects).

## Context

The plan resumed once after an interpreter outage, in a second session. `status.metadata.session_ids` still holds only the first session id, and `session capture` at retrospective start stored the first id again. Session-keyed consumers (metrics enrich from transcripts, the chat-history aspect) therefore see only part of the run.

## Root cause

The resume path does not capture the current session id into the plan; capture reads the stored id rather than the live one.

## Proposed action

On every plan entry (resume, re-entry, finalize dispatch from a new session), compare the live session id with `session_ids` and append it when new. Make the chat-history and metrics consumers iterate all recorded ids.

## Evidence

- aspect: logging_gap_analysis - session_ids[1] = f467efbd-..., post-resume session absent
- aspect: chat_history_analysis - transcript analysed for the first session id only
