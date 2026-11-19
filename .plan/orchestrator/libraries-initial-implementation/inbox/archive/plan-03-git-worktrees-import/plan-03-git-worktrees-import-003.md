envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:00Z

# Candidate lesson: phase_handshake reports drift after the orchestrator resolves outline Q-Gate findings

- Source: script-failure cluster (signal-scan class script_failure), notation plan-marshall:plan-marshall:phase_handshake
- Plan: plan-03-git-worktrees-import, 2026-11-18T15:05:22Z, phase 3-outline -> 4-plan transition
- Marker: [ERROR] (plan-marshall:execute-script:1) script_failure ... exit_code=1 failure_kind=script_internal_failure

## What happened

The 3-outline handshake invariants were captured while two Q-Gate findings (86382b, f95cde) were pending.
The orchestrator then resolved both as taken_into_account and called phase_handshake, which returned
status: drift with diffs qgate_open_count 2 -> 0 and pending_findings_blocking_count 2 -> 0, exit 1.
The orchestrator re-captured the 3-outline invariants and proceeded; the drift was its own legitimate
resolution, not an out-of-band change.

## Possible rule

Resolving the phase's own Q-Gate findings between capture and verify is the expected path, not drift.
Either re-capture invariants after Q-Gate resolution as a documented step, or have phase_handshake treat
a decrease in open/blocking Q-Gate counts that is explained by resolved findings as non-drift.
