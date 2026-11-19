envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:57:39Z

# Candidate lesson: log a disabled build server once per plan, not as a WARNING per build

- Source: plan-retrospective (logging-gap-analysis, log-analysis); work log hash 16697f, 36 occurrences
- Suggested component: plan-marshall:build-server-client (the [BUILD-SERVER] build-resolution line)
- Suggested category: improvement
- Confidence: high

## Context

Every one of the plan's 36 maven builds logged `[WARNING] [BUILD-SERVER] resolved build (requested=auto, resolved=in_process, reason=disabled, ... mechanism=in_process_fallback, serialization=fallback-slot)`. These are 36 of the plan's 37 work-log warnings.

## Root cause

A build server that is disabled by configuration is an expected, configured state, but the resolution is logged at WARNING on every build. The warning channel then carries no information, and a real warning in the same plan is easy to miss.

## Proposed action

Log the `reason=disabled` fallback at INFO, or log it at WARNING only once per plan (first build), and keep WARNING for unexpected fallbacks (daemon unreachable, enrolment mismatch).

## Evidence

- log-analysis: warnings_work 37; work.log shows one identical [BUILD-SERVER] WARNING line before each of the 36 builds.
