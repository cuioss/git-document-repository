envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:30Z

# Candidate lesson: cleanup guard in LocalRepositoryExtensionTest could be satisfied by one of two directories

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding e0286c, git-testing/src/test/java/de/cuioss/git/document/testing/LocalRepositoryExtensionTest.java:84). Fixed by TASK-12, commit 3ffe210.

## Finding

`shouldProvideDistinctRepositories` receives two directories. The guard passed if only the first was recorded, so `everyDirectoryIsDeleted` never checked the second path. A recording made by another test could also satisfy the guard.

## Candidate rule

Track cleanup recordings per invocation and compare their count with the number of directory-backed fixture parameters. Sibling of finding 7acc82 (LocalRemoteExtensionTest); the two share one root cause and may merge into one lesson.
