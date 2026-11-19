envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:14Z

# Candidate lesson: rollback skipped directory deletion when close() threw

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 49b2be, git-testing/src/main/java/de/cuioss/git/document/testing/LocalRepositoryExtension.java:173). Fixed by TASK-12, commit 3ffe210.

## Finding

`LocalRepositoryExtension.rollBack` promises to close the repository and delete its directory. If `repository.close()` throws, deletion is skipped and the close failure masks the original `ParameterResolutionException`.

## Candidate rule

In a multi-step cleanup, a failure of one step must not skip the next: catch the close failure, attach it as suppressed to the original exception, and still attempt deletion. Cover it with a test that uses a throwing double; the normal path must still delete without adding suppressed exceptions.
