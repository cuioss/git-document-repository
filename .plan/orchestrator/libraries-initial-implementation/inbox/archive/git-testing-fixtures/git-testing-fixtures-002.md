envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:01Z

# Candidate lesson: the change list named state cells but missed the "needs approval" sentences in the same files

- Source signal: Q-Gate finding 8feafc (phase 4-plan, scope_criterion_validator, under_coverage, deliverable 3, severity warning)
- Resolution in run: fixed. Task 5 was changed to name the two sentences. The outline stayed unchanged.

## What happened

A success criterion said that no changed document may still say the owner's approval of JGit is pending. Two changed files still held sentences that the change list for the file did not name:

- doc/concepts/module-structure.adoc lines 97-98 ("Every one of these dependencies, JGit included, needs the owner's approval")
- doc/specification/general.adoc lines 28-30 ("Every other dependency below is planned and needs the owner's approval")

## Candidate rule

This is the second finding of the same kind as 9fe2d8 in this run, and it came one phase later. When a criterion is a content rule over changed files ("no file says X"), run that content search during planning. Name every match in the change list for its file. Do not list only the obvious state cells.

Classification is deferred to the orchestrator.
