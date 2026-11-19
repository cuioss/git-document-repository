envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:56Z

# Candidate lesson: in-memory repository closure was not observed by any assertion

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 8c83f1, git-testing/src/test/java/de/cuioss/git/document/testing/PerClassLifecycleTest.java:188). Fixed by TASK-11, commit 3ffe210.

## Finding

The test passed even if the resolver never closed the constructor `@InMemory` repository: `getFullBranch()` reads `HEAD`, and the teardown callbacks check only filesystem paths. A successful branch read also does not prove the repository is still open, because JGit can reopen its in-memory ref reader after it is cleared (claim from the review bot; JGit version not re-checked here).

## Candidate rule

For a resource with no filesystem footprint, observe `close()` itself through a test hook on the registered instance: zero closes before the scope ends, exactly one after. Do not infer open/closed state from a read succeeding. Shares its root with finding c59757 (spec claim narrowed to what is observed).
