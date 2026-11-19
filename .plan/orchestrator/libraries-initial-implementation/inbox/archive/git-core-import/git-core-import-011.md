envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:50:04Z

# Add a rate-window termination cause to record-dispatch-boundary

component=plan-marshall:manage-metrics category=improvement source=plan-retrospective plan=git-core-import

## Context

In plan git-core-import the automatic-review dispatch at 2026-11-18T12:35:53Z returned `escalate_ask` with reason
`rate_window_await`: CodeRabbit's quota window was armed for 56 minutes. Its dispatch-boundary row carries
`termination_cause: blocked_session_restart` (118077 tokens), so the retrospective counts a bot-quota wait as a
session-restart retryable spend. Nothing restarted.

## Root cause

The `--termination-cause` choice set (`DISPATCH_TERMINATION_CAUSES`) has no value for an external rate-window wait.
The recorder therefore picks the nearest retryable bucket, and the cause stops saying what happened.

## Proposed action

Add a cause such as `rate_window_await` to `DISPATCH_TERMINATION_CAUSES`, keep it in the retryable class, and have the
automatic-review escalate path record it. Update the logging-gap-analysis cause list to match.

## Evidence

- aspect: log_analysis: 6-finalize dispatch_boundaries row 12:35:53Z, blocked_session_restart, retryable_total_tokens
  118077
- aspect: logging_gap_analysis: DISPATCH_TERMINATION_CAUSE gap on 6-finalize
- status.json halts.6-finalize.automatic-review: reason "escalate_ask{reason: rate_window_await}"
