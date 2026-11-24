envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:32:09Z

# Candidate lesson: 6-finalize inline commit refused with no_sanction

Source: script-failure cluster, notation `plan-marshall:workflow-integration-git:git-workflow`. Work-log ERROR at 2026-11-23T21:53:22Z, phase 6-finalize, right after the wait-region-unified-triage dispatch in loop-back iteration 2.

Failure: exit_code=1, failure_kind=script_internal_failure, error_code INVALID_INPUT, refused: true, reason: no_sanction. The message was "Source-tree commit refused in 6-finalize: no step id from the composed phase-6 manifest, no open loop-back fix task and no review-rubric section-6 inline-correction record". The changed path was under `git-store/src/test/...`.

What happened next: the run recovered. Loop-back iteration 3/3 then recorded an inline fix (2779c80) at target 6-finalize. The first commit attempt was made without one of the three sanctions the commit guard needs.

Possible lesson: before committing a source edit during 6-finalize, the inline-correction path should first establish its sanction, either by passing `--step` with a composed phase-6 step id or by recording the section-6 inline correction. Otherwise the commit guard refuses and a retry is needed. This may point to a sequencing gap in the triage or inline-fix workflow rather than an agent error.
