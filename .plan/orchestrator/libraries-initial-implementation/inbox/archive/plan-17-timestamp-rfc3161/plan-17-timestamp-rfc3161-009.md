envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:27:54Z

component=plan-marshall:phase-2-refine
category=bug
signal=retrospective-audit
shape=actionable

# Refine re-entry for baseline drift is always blocked by the 1-init handshake verify

## What happened

In plan-17-timestamp-rfc3161 an execute envelope stopped on baseline drift (upstream e38e079, 3 doc conflicts) and the documented recovery was to loop back through refine. The refine leaf halted at entry: `phase_handshake verify --phase 1-init --strict` returned drift (task_state_hash, unfinished_tasks_count 0->1, pending sonar findings 0->2). That drift is exactly what any loop-back from 5-execute produces, so the documented recovery path cannot run without an operator override. The operator chose "Skip refine, rebase now". The `2-refine` phase stayed `in_progress` in the phase table for the rest of the plan.

## Corrective action

- When refine is entered as a loop-back re-entry (`metadata.loop_back_reentry` set), verify the 1-init handshake only on the invariants a loop-back cannot legitimately move (config_hash, main_dirty), or verify against the latest captured phase instead of 1-init.
- Or route baseline-drift recovery to a dedicated rebase/reconcile step instead of a full refine re-run when the refine result (confidence, track, pr_title) is already persisted.
- Close or supersede the `2-refine` phase status when the operator bypasses the re-entry, so the phase table does not show a phase in progress after the plan landed.

## Impact

One operator escalation, one wasted refine dispatch, and a stale in_progress phase in a merged plan.
