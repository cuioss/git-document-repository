envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:24Z

# Candidate lesson: fixture rollback must also cover unchecked exceptions from JGit repository creation

- Source signal: automatic-review, remediated review-bot finding 83c609 (coderabbitai, PR #23, LocalRepositoryExtension.java:130 and 135)
- Resolution in run: fixed, commit f5c11ce

## What happened

The rollback around repository construction and `repository.create(true)` caught only `IOException`. JGit 7.8 reads inherited Git configuration during `create`. A value such as `core.hideDotFiles=invalid` then raises `IllegalArgumentException`. In that case the repository stayed open, and its temporary directory was never registered for cleanup.

## Candidate rule

When a fixture constructs a JGit repository and then initializes it, the rollback must cover `RuntimeException` as well as `IOException`. It must close any repository it constructed, delete the directory, and keep cleanup errors as suppressed exceptions. JGit throws unchecked exceptions for bad configuration values, so a catch on `IOException` alone is not enough.

Classification is deferred to the orchestrator.
