envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:06Z

Candidate lesson (script failure: plan-marshall:workflow-integration-git:git-workflow, commit refused in 6-finalize twice)

What happened: Twice in 6-finalize (17:06:14 with 45 changed paths, and 19:36:20 with 2 git-store paths) a source-tree commit was refused with exit_code=1. The message was: "Source-tree commit refused in 6-finalize: no step id from the composed phase-6 manifest, no open loop-back fix task and no review-rubric section-6 inline-correction record" (reason: no_sanction, step: null). The finalize flow then continued through finalize-step-simplify, pre-push-quality-gate and the loop-backs.

Evidence: [ERROR] script_failure lines 5d0294 and 2e4302 (failure_kind=script_internal_failure).

Suggested rule: A commit attempted in 6-finalize must carry its sanction, either `--step` with the composed phase-6 step id (for example finalize-step-simplify) or an open loop-back fix task. The caller that issued these commits omitted `--step`. The finalize step that commits should pass its own step id by construction.
