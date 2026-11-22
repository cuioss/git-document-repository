envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:21Z

component=plan-marshall:phase-4-plan
category=improvement

# git-store has an undeclared-empty skills_by_profile.implementation, so tasks get persona-only skills

Source: Q-Gate finding 0f7b46 (phase 4-plan, resolution taken_into_account).

## What happened

Phase 4-plan reported that module `git-store` has an undeclared-empty `skills_by_profile.implementation`. The implementation tasks (1, 3, 5, 7) were created with persona-resolved skills only. The orchestrator added the `cui-logging` skill that the plan spec requires by hand. The architecture enrichment of `git-store` was left to the project owner.

## Rule

When a staged plan spec names domain skills for a module whose `skills_by_profile` is undeclared-empty, either enrich the module (`architecture enrich`) before phase 4, or declare the profile `minimal: true` on purpose. Otherwise every plan on that module repeats the same Q-Gate finding and the same manual skill fix-up.

## Recurrence hint

This is the second module plan of the epic that touches `git-store`. Later git-store plans will hit the same finding until the module is enriched.
