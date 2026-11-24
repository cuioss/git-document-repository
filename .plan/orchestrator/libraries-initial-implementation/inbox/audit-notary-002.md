envelope_version=1
sender_type=orchestrator
sender_id=audit-notary
epic=libraries-initial-implementation
kind=finding
created=2026-11-24T19:59:07Z

# Consumer request (Audit Notary): stages within a document, and a release check that skips stages that do not apply

Source: cuioss/Audit-Notary, branch `feature/staged-documents`, the concept `doc/concepts/staged-documents.adoc` and § "Stages of an Incident" of `doc/concepts/category-incident-reports.adoc`. Requested by the owner on 2026-11-24.

## What the consumer decided

An incident file follows a process whose steps are grouped as tabs: triage, a data protection assessment (only for a personal data breach), response, notifications, review. Other kinds will follow the same pattern: supplier assessments and exception approvals. No process is built in. A kind declares its *stages* in its `workflow.yaml`:

* A stage is one top-level schema property of `"type": "object"` with `"gdr-split": true`, so it is a part file of its own. A property belongs to at most one stage.
* `after`: the stages that must be completed, or not applicable, before this one can be completed.
* `when`: the condition under which the stage applies. It uses the condition language the deadline rules need, extended by `any` and `all`.
* `narrative`: the fragments of the stage.
* `complete.step-up`: as for a transition (GDR-FORMS-7), default `false`.
* Status: `not-applicable` and `open` are derived from the data. `completed` is stored in the part as `completion: {at, by}`, where `by` is a pseudonym.
* Editing is never gated. Completing a stage is gated by its predecessors and by the `required` findings whose JSON Pointer starts with the part. A completed part is read-only until it is reopened; reopening removes `completion` in a save.
* The transition out of the first state and every release require every applicable stage to be completed. The `required` fields of a stage that does not apply are not checked. This gives conditional mandatory fields, which the schema cannot express, because GDR-FORMS-2 refuses `required` under `if`, `then` and `else`.

## What git-forms lacks (as of main e38e079)

1. **The release check covers the whole document.** GDR-FORMS-2 checks `required` on the whole document at the transition out of the first state and at every release. A document whose data protection stage does not apply could never be released while that part's mandatory fields are empty.
2. **The workflow has no stages.** GDR-FORMS-5 has no declaration for them. Nothing gates submission or release on the completion of the applicable stages, and nothing keeps a completed part read-only.
3. **Conditions on fields.** These are the same extension of the deadline rules that Schema-Driven Documents, Open Questions 3 already lists.

## Requested change

Preferred: stages as a concept of `git-forms`, beside GDR-FORMS-5.

* `stages` in `workflow.yaml`, validated when the workflow is loaded: the part exists and carries `gdr-split`, no part belongs to two stages, `after` names known stages, and there are no cycles.
* Stage status computed from the data and available in the index.
* `complete` and `reopen` operations, each with the mechanism `save` and optional step-up.
* The transition out of the first state and every release are refused with an outcome that names the open stages, and they check `required` only for the document part and the applicable stages.

Minimal alternative, if the library keeps stages out: the `required` check at those transitions accepts from the caller a set of top-level parts to exclude.

## Why the library and not the consumer

The library enforces `required` at those transitions (GDR-FORMS-2). The consumer cannot exempt a part without bypassing that check. Stages are as generic as the rest of schema-driven documents; Audit Notary's open question 1 in `staged-documents.adoc` proposes the library as their home.

## Done when

The concept (Schema-Driven Documents), GDR-FORMS-2 and GDR-FORMS-5, and the implementation in `git-forms` have stages, with tests for these cases:

* A stage that does not apply, with mandatory fields missing, does not block a release.
* An applicable stage that is still open blocks submission.
* Completion is refused while a predecessor is open.
* Completion is refused while a mandatory field of the stage's part is missing.
* Reopening removes `completion`.
