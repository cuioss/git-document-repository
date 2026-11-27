envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:34Z

# Candidate lesson: operator decision to widen deliverable scope beyond the request's file list

- Source signal: Q-Gate finding d1764f (phase 3-outline, source user_review), resolution taken_into_account
- Plan: plan-14-forms-deadlines-links-numbers
- Related: finding a068a0
- Component hint: plan-marshall:phase-3-outline

## Observation

The operator decided to add `doc/concepts/git-as-document-persistence.adoc` (lines 52-53, step-up requires MFA) to deliverable 6
so it is reworded together with the other step-up documents, and explicitly not to edit ADR-0005. The request's scope list had
not named this file.

## Possible rule

A request's explicit file list for a wording change is a floor, not a ceiling: documents that restate the same rule belong to
the change. For this repository, ADRs are historical records and are not reworded when a later change supersedes their wording;
the concept and specification documents are.
