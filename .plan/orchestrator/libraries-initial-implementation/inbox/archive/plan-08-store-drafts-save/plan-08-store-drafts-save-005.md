envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:10Z

# Candidate lesson: git-store has an undeclared-empty skills_by_profile.module_testing

- Signal: Q-Gate finding (4-plan), hash b4bc7f, resolution accepted
- Title: "Unresolved skills_by_profile: git-store.module_testing"
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

Module `git-store` has an undeclared-empty `skills_by_profile.module_testing`, so the test tasks ran on persona-resolved skills only. The operator accepted it as is. This is the same gap as the implementation profile (separate message).

## Possible rule (for the orchestrator to classify)

Enrich `git-store` with a module_testing profile (junit-core, cui-testing), or declare it deliberately minimal. This is a configuration follow-up, not a defect lesson.
