envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:16:35Z

# Give the learned maven build timeout headroom above the observed run time

Component: plan-marshall:build-maven. Category: bug. Confidence: high. Source: plan-retrospective.

## Context

In plan-23-forms-hardening the wrapper's learned build timeouts were 305 to 315 s, while the whole-tree `verify -Ppre-commit` runs took up to 316 s (slowest logged calls 316,360 ms, 306,620 ms, 277,500 ms). The deliverable-6 gate passed all 1450 tests and was then stopped by the wrapper's 315 s limit during the javadoc jar step; the leaf had to re-run it with `-DskipTests` to cover lint and javadoc.

## Root cause

The adaptive timeout learns a value at or just above the measured run time, with no safety margin, so normal variance on a full run crosses it.

## Proposed action

Apply a multiplicative headroom (for example 1.5x, floored at the 600 s Bash ceiling) to the learned build timeout, and never learn a limit below the longest successful run seen for the same command.

## Evidence

- aspect: log_analysis - slowest_scripts plan-marshall:build-maven:maven 316360, 306620, 277500 ms; 39 calls at or over the 30 s ceiling
- aspect: chat_history_analysis - envelope-2 hand-back: "the wrapper's learned 315s limit stopped it during the javadoc jar step"; "learned build timeouts (305 to 315s) sit right at this machine's real run time"
