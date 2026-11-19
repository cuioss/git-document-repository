envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:22Z

# Candidate lesson: cleanup guard in LocalRemoteExtensionTest did not count every fixture directory

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 7acc82, git-testing/src/test/java/de/cuioss/git/document/testing/LocalRemoteExtensionTest.java:81). Fixed by TASK-12, commit 3ffe210.

## Finding

`shouldServeFetch` receives two fixture directories, but the guard passed if only the remote directory was recorded. `everyDirectoryIsDeleted` then checked only that one path and could not detect a leak of the local directory.

## Candidate rule

A cleanup guard must require one record per directory-backed fixture parameter of the invocation (derive the expected count from the method and constructor parameters and compare with the distinct paths recorded by that invocation), not "at least one record". Sibling finding e0286c reports the same gap in LocalRepositoryExtensionTest.
