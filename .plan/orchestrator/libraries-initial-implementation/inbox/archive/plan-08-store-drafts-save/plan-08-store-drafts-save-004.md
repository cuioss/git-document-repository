envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:05Z

# Candidate lesson: git-store has an undeclared-empty skills_by_profile.implementation

- Signal: Q-Gate finding (4-plan), hash 41262d, resolution accepted
- Title: "Unresolved skills_by_profile: git-store.implementation"
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

Module `git-store` has an undeclared-empty `skills_by_profile.implementation`, so the implementation tasks ran on persona-resolved skills only. The operator accepted it as is. This is the second plan touching git-store (after the store foundation), and the gap is still there.

## Possible rule (for the orchestrator to classify)

Run architecture enrichment for `git-store` (implementation profile: java-core, java-null-safety, javadoc, cui-logging), or declare the profile deliberately minimal, so later git-store plans stop raising this finding. This is a project-configuration follow-up, not a defect lesson.
