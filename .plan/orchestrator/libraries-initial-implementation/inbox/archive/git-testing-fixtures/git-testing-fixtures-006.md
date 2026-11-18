envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:31Z

# Candidate lesson: a Files.exists precheck before deletion hides access failures

- Source signal: automatic-review, remediated review-bot finding 694ea4 (coderabbitai, PR #23, TemporaryDirectories.java:60)
- Resolution in run: fixed, commit f5c11ce

## What happened

`TemporaryDirectories.delete` returned early when `Files.exists(directory)` was false. `Files.exists` also returns false when it cannot tell whether the file exists, for example when the parent directory is not accessible. In that case cleanup reported success without deleting anything, and `visitFileFailed` never ran.

## Candidate rule

Do not guard a recursive delete with `Files.exists`. Let the walk run, and let the visitor treat `NoSuchFileException` as the "already gone" case. Every other failure should propagate. More generally: check that a guard's predicate actually matches the scenario the guard exists for. "Cannot tell" is not the same as "absent".

Classification is deferred to the orchestrator.
