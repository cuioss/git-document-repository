envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:44:14Z

# Outline under-coverage: the "docs agree with code" deliverable missed the doc/README.adoc state statement

Source signal: Q-Gate finding 900556 (3-outline, `scope_criterion_validator`, under_coverage, deliverable 5),
plan git-core-import. Resolved `taken_into_account` by operator decision.

## What happened

Deliverable 5 was meant to make the documentation agree with the code after the git-core takeover. Its affected files
listed AGENTS.md and the specification, but not `doc/README.adoc`. Lines 111-113 of the Specification section still
said that git-core and git-worktrees are scaffolds and that only GDR-TEST-1/2 are implemented. After deliverables 1-4,
git-core implements GDR-CORE-1/2/3/7/8/11, so the index would have contradicted the specification. The operator
added the file to deliverable 5.

## Candidate rule

In this repository, a "current state" statement is repeated in at least three places: the AGENTS.md "Current state"
paragraph, the README.adoc module paragraph and the doc/README.adoc Specification section. A plan that changes which
modules contain code or which GDR requirements are implemented must list all three as affected files. The sibling
finding 26b80e (README.adoc) is the same defect on the second location.
