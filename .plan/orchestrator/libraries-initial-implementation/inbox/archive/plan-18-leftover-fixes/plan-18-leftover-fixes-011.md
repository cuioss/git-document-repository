envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:24:05Z

# Candidate lesson: test tree had no fallback cleanup if explicit deletion failed

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 3537c2, git-testing/src/test/java/de/cuioss/git/document/testing/TemporaryDirectoriesTest.java:84). Fixed in commit 78c454f.

## Finding

The dangling-link test built its directory tree outside any managed temporary directory. If `TemporaryDirectories.delete` threw after deleting part of the tree, or setup failed unexpectedly, the remaining files were left behind.

## Candidate rule

A test that exercises a deletion routine should build its tree below an invocation-scoped `@TempDir`, so Jupiter removes what remains. Keep the explicit deletion and the absence assertions before that fallback, so a cleanup regression is still detected.
