envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:42Z

# Candidate lesson: git-forms has no skills_by_profile.module_testing in the architecture data

- Source signal: Q-Gate finding ba383d (phase 4-plan, triage), resolution taken_into_account
- Plan: plan-14-forms-deadlines-links-numbers
- Related: finding 2988d1
- Component hint: plan-marshall:manage-architecture (module git-forms)

## Observation

Module `git-forms` has an undeclared-empty `skills_by_profile.module_testing`. Every module_testing task was created with an
empty architecture skill set and the persona resolver supplied 8 skills instead. Taken into account as not a plan defect.

## Possible rule / follow-up

Same remedy as 2988d1: enrich `git-forms` (`skills_by_profile.module_testing`) once, at the epic level, rather than
accepting the same finding in every plan of the epic.
