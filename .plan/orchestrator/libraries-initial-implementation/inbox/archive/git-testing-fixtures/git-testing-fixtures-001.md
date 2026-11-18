envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:51:52Z

# Candidate lesson: outline missed stale "JGit approval pending" statements outside the deliverable

- Source signal: Q-Gate finding 9fe2d8 (phase 3-outline, scope_criterion_validator, under_coverage, deliverable 3, severity warning)
- Resolution in run: taken_into_account. The operator chose to add both files to deliverable 3.

## What happened

Deliverable 3 updated the documentation for JGit becoming a managed, declared dependency. Two documents outside its affected files still said the owner's approval of JGit was pending:

- doc/specification/git-core.adoc line 9
- doc/concepts/git-as-document-persistence.adoc, Open Questions item 2

## Candidate rule

When a plan changes the state of a dependency or decision, search the whole doc corpus for statements of the old state (for example "pending approval" or "planned"). Do not search only the files the outline already names. The affected-files list should come from that search.

Classification is deferred to the orchestrator.
