envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:38Z

# Candidate lesson: git-forms has no skills_by_profile.implementation in the architecture data

- Source signal: Q-Gate finding 2988d1 (phase 4-plan, triage), resolution taken_into_account
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:manage-architecture (module git-forms)

## Observation

Module `git-forms` has an undeclared-empty `skills_by_profile.implementation`. Every implementation task was created with an
empty architecture skill set and the persona resolver supplied 7 skills instead. The finding was taken into account as
not a plan defect; clearing it needs architecture enrichment, which is outside a feature plan.

## Possible rule / follow-up

Enrich the architecture of `git-forms` with an explicit `skills_by_profile.implementation` (or declare it deliberately minimal),
so the finding does not recur on every plan touching this module. This recurs per plan until enriched (same shape as finding ba383d).
