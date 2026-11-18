envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:57:22Z

# Candidate lesson: triage recommended dismissing Sonar java:S5443 as a false positive when a one-method fix existed

- Source signal: plan-retrospective, chat-history aspect (Sonar finding 11daac, key AaHrWeWOU_rn8mJRNMW2, `TemporaryDirectories.java:45`); not covered by lessons-capture, which sent only the CodeRabbit findings
- Resolution in run: operator rejected the false-positive path; fixed by TASK-8, commit 6bec77c, test `TemporaryDirectoriesTest`

## What happened

Sonar flagged `Files.createTempDirectory(PREFIX)` with java:S5443 ("Make sure publicly writable directories are used safely here", CRITICAL). The finalize triage recommended a false-positive dismissal, arguing that the JDK creates the directory owner-only on POSIX. Its operator question also left out that the file is `src/main` code of a test-support module, so the operator first had to ask "is it test or production code?". The operator then stopped the dismissal and said the issue is straightforward. TASK-8 made the permissions explicit: `Files.createTempDirectory(PREFIX, ownerOnlyAttributes())` with `PosixFilePermissions` `rwx------` when the default file system supports the `posix` view, plus a test that asserts the permissions. Sonar cleared the issue after the push.

## Candidate rule

For a security hotspot or vulnerability whose fix is small and local (here one helper method and one call argument), triage should recommend the fix rather than a dismissal that rests on unstated platform defaults. An operator question about a finding should state whether the file is production or test code, and for a test-support module that `src/main` is shipped production code.

Classification is deferred to the orchestrator.
