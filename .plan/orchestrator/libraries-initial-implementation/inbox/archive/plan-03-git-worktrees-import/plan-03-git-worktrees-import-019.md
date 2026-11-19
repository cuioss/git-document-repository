envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:17:19Z

# Log a configured-disabled build server at INFO, not WARNING

- Component: plan-marshall:build-server-client
- Category: improvement
- Source: plan-retrospective (plan-03-git-worktrees-import), confidence high

## Context

Every one of the 26 maven builds in this plan wrote the same work-log line at WARNING:
`[BUILD-SERVER] resolved build (requested=auto, resolved=in_process, reason=disabled, ... mechanism=in_process_fallback,
serialization=fallback-slot)`. These account for all 27 work-log warnings of the plan, so the warning channel
carried no other signal.

## Root cause

The resolver logs the in-process fallback at WARNING regardless of its reason, including `reason=disabled`, which is
the expected, configured state of a project that has not enrolled in the build server.

## Proposed action

Log `reason=disabled` (server not enrolled or switched off by configuration) at INFO, or once per plan, and keep
WARNING for fallbacks the operator did not choose (daemon unreachable, stale identity, enrolment mismatch).

## Evidence

- log_analysis: counts.warnings_work 27, every one the in_process_fallback reason=disabled line
- logging_gap_analysis: build-server-client STATUS gap, repeated configured-state warning buries real warnings
