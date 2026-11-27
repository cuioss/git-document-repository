envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:36Z

# Stop the harness backgrounding builds over 180s despite the 600000ms timeout

- Source: plan-retrospective (chat-history-analysis, log-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:platform-runtime (harness bash-timeout-ceiling seam) / plan-marshall:build-maven
- Category: bug
- Confidence: high

## Context

In execute envelope 2 both `verify -Ppre-commit` runs (about 190-200 s) and in envelope 6 the last two full builds were moved to the background after 180 s, although the Bash call carried a 600000 ms timeout. The leaves waited for the completion notice and read the result, and the task notifications show agents "waiting on its own background work".

## Root cause

The effective synchronous ceiling on this harness is below the value the bash-timeout-ceiling seam resolves (600000 ms), so the documented "synchronous Bash IS the wait" contract does not hold for builds longer than about 3 minutes.

## Proposed action

Re-measure the harness's real synchronous ceiling and either resolve it through the seam (so builds over it route to the orchestrator-tier await path) or document the auto-background behaviour and the safe wait pattern for leaves.

## Evidence

- envelope 2 hand-back: "The harness moved both verify -Ppre-commit runs ... to the background after 180 s, despite the 600000 ms timeout"
- envelope 6 hand-back: "The last two full builds ran longer than 180 s, and the harness moved them to the background"
- log-analysis: maven max call 258 s, 48 calls at or over the 30 s ceiling
