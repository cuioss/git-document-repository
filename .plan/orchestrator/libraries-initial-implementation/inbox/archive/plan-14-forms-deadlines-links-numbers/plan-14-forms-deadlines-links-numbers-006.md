envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:46Z

# Candidate lesson: a specification-consistency test checked only one direction

- Source signal: review-bot finding b6b2be (CodeRabbit, PR #81, doc/specification/git-forms.adoc:17), resolution fixed by TASK-15 (40cbe3d)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-forms tests (FormsOutcomeSpecificationTest)

## Observation

`FormsOutcomeSpecificationTest` failed when a `FormsOutcome` constant was missing from the outcome list in the introduction of
`doc/specification/git-forms.adoc`, but not when the introduction named an outcome that no longer exists. A constant removed from
the code and from the GDR-GEN-3 table would have stayed in the introduction unnoticed.

## Possible rule

A test that keeps code and specification in step must check both directions: every code constant is named in the document,
and every name in the document is a code constant. This applies to every list-in-sync test of this repository (outcome lists,
log message identifiers, requirement identifiers).
