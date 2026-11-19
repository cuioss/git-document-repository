envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:04Z

# Candidate lesson: a requirement-state documentation deliverable must cover the root README.adoc

- Source: Q-Gate finding 04f7cd (phase 4-plan, scope_criterion_validator under_coverage, deliverable 4), resolution taken_into_account
- Component: project documentation (README.adoc, doc/README.adoc, doc/specification/general.adoc, doc/concepts/module-structure.adoc, AGENTS.md)

## Observation

Deliverable 4 stated GDR-CORE-4 and GDR-CORE-5 as implemented across the documents, but its affected files omitted the root README.adoc, whose module table and git-core content list would then describe the git-core state incompletely and differently from doc/README.adoc, general.adoc, module-structure.adoc and AGENTS.md. A content search over the implemented-state wording surfaced it.

## Resolution in this plan

README.adoc was added as step 6 of TASK-009 with the consistent git-core wording.

## Candidate rule

In this repository the git-core implemented-state wording lives in at least five places (root README.adoc, doc/README.adoc, doc/specification/general.adoc, doc/concepts/module-structure.adoc, AGENTS.md). A deliverable that changes the module's state must list all of them; derive the list by a content search over the state wording, not from memory. Four of them (doc/README.adoc, AGENTS.md, README.adoc, doc/specification/general.adoc) also conflicted by hand at the finalize rebase onto origin/main, because sibling plans update the same state lists.
