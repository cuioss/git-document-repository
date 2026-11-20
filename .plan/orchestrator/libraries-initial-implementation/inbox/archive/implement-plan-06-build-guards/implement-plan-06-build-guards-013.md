envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:21:12Z

# Do not stamp an infeasible leaf return as termination_cause error

Component: plan-marshall:phase-5-execute (category: bug, confidence: high)

## Context

The 5-execute envelope that ran TASK-8 returned `status: infeasible` with a verified reason (GraalVM's resource-config.json must be a top-level object). Its dispatch boundary was recorded with `termination_cause: error` at 195152 tokens, so the log analysis reports `error_total_tokens: 195152` for 5-execute - spend the logging-gap contract defines as "genuinely non-productive: raised a fatal error and returned nothing".

## Root cause

The caller that records the dispatch boundary maps an `infeasible` return onto `error`; the `--termination-cause` set has no cause for a leaf that stopped on purpose and handed a decision back.

## Proposed action

Map an `infeasible` return to an existing hand-back cause (e.g. `blocked_user_review`) or add a dedicated cause, so the error figure keeps meaning terminal waste.

## Evidence

- aspect: log-analysis - dispatch_boundaries 5-execute row 2026-11-20T06:12:50Z termination_cause=error, total_tokens=195152
- aspect: plan-efficiency - error_total_tokens 195152 counted against a dispatch that returned a real format finding
- aspect: logging-gap-analysis - DISPATCH_TERMINATION_CAUSE gap
