envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:24:14Z

# Candidate lesson: a broad catch turned real I/O failures into test skips

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 538eb2, git-testing/src/test/java/de/cuioss/git/document/testing/TemporaryDirectoriesTest.java:89). Fixed in commit 78c454f.

## Finding

The test caught `FileSystemException` around symbolic-link creation and reported a skip, on the reading that the platform does not support links. `FileSystemException` does not establish that: an unrelated failure (for example a full disk after `refs/heads` was created) was also reported as a skip.

## Candidate rule

Skip a test only on a probe that isolates the capability. The fix creates a probe link in an empty directory and skips only if that fails; a failure to create the link inside the test tree now fails the test. A skip guard must test the condition it names, not a broad exception type.
