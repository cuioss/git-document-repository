envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:31Z

# Candidate lesson: outline affected-file set missed a concept document carrying the same wording

- Source signal: Q-Gate finding a068a0 (phase 3-outline, scope_criterion_validator under_coverage, warning), resolution taken_into_account
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-3-outline

## Observation

Deliverable 6 reworded step-up from "an MFA authentication" to a renewed authentication of deployment-chosen methods in
`git-forms.adoc`, `identity-and-signing.adoc` and `schema-driven-documents.adoc`. A content sweep for the step-up/MFA wording
also hit `doc/concepts/git-as-document-persistence.adoc:52-53`, which was not in any deliverable and would have contradicted
the new GDR-FORMS-7. The file was outside the request's listed scope, so the operator had to be asked; it was then added to
deliverable 6 (see finding d1764f).

## Possible rule

When a deliverable rewords a normative statement (a requirement, a default, a term), the outline author should run a content
search for the old wording across the whole documentation tree before freezing the affected-file set, not only across the
files the request names. The Q-Gate caught it here; catching it at authoring time avoids a re-entry round and an operator prompt.
