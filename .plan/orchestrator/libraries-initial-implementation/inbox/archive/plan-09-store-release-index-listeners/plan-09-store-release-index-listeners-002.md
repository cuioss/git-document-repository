envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:25Z

component=plan-marshall:phase-4-plan
category=improvement

# git-store has an undeclared-empty skills_by_profile.module_testing, so test tasks get persona-only skills

Source: Q-Gate finding 3f11a5 (phase 4-plan, resolution taken_into_account).

## What happened

Phase 4-plan reported that module `git-store` has an undeclared-empty `skills_by_profile.module_testing`. The test tasks (2, 4, 6, 8) were created with persona-resolved skills only. The orchestrator added the `cui-testing` and `cui-logging` skills that the plan spec requires by hand.

## Rule

Same root cause as finding 0f7b46 (implementation profile): enrich `git-store` once, for both profiles, or declare them `minimal: true`. Do not fix each plan's tasks by hand.
