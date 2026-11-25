envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:25:42Z

## Candidate lesson: dispatch-boundary rows carry no step id, so the ledgers cannot be joined

Signal: retrospective (logging-gap analysis and execution-context dispatch audit).

What happened: the plan recorded 19 dispatch-boundary rows (4-plan 1, 5-execute 4, 6-finalize 14). None of them carries a `step_id`, because `record-dispatch-boundary` was called without `--step-id`. The dispatch audit's `firing_comparison` therefore paired 0 firings against 43 `execution_log` rows and fell back to time windows. The four context-load columns are `unmeasured` on all 19 rows, and 28 of the 43 `record-step` rows (every pre-push-quality-gate, push, ci-verify, branch-cleanup and verify step) are `unmeasured` too, so four finalize steps show as `no_evidence` in the coverage audit.

Suggested rule: the orchestrator passes `--step-id` (the same key `record-step` writes) on every `record-dispatch-boundary` call, and forwards the four context-load figures when the dispatch return carries them. Add a check that fails when a dispatching phase has boundary rows and none of them is keyed.

Component hint: plan-marshall:plan-marshall (orchestrator dispatch sites) and plan-marshall:phase-6-finalize (per-step dispatch).
