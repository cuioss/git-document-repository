envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:37Z

component=plan-marshall:phase-6-finalize
category=improvement

# Small fix rounds re-fire simplify, the pre-push gate and ci-verify in full each time

Source: orchestrator observation in this run (work log: re-fire lines at 2026-11-21T16:17:44Z, 16:18:39Z, 16:21:26Z and the round-2 completions at 16:51-16:56).

## What happened

The finalize loop went back to 5-execute twice for review and Sonar fixes (TASK-10..15, then TASK-16..18). Each round was small. After each round, and once more for the first pass, `finalize-step-simplify`, `pre-push-quality-gate` and `ci-verify` ran again in full, three times in total. The simplify re-fire was triggered only because "HEAD advanced, verdict_inputs undeclared".

## Rule

A re-fire should be scoped to what the fix round changed. Simplify should run only on the paths the round touched, or be skipped when `verdict_inputs` would show nothing new. The pre-push gate is required. ci-verify and the bot review rounds are where the time goes, so fix rounds should be batched: collect the Sonar and review findings from one CI run before going back, instead of one go-back per source.
