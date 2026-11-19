envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:22:52Z

# Candidate lesson: specification claimed cleanup coverage the test did not observe

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding c59757, doc/specification/git-testing.adoc:115). Fixed by TASK-11, commit 3ffe210.

## Finding

The specification stated that `PerClassLifecycleTest` verifies cleanup of the per-class fixtures. The test checks only that the local and remote constructor directories are deleted; it does not observe closure of the in-memory constructor repository, whose assertions (branch, identity) would still pass if `close()` were never called.

## Candidate rule

A specification claim of the form "test X verifies Y" must be matched by an assertion that would fail if Y did not happen. Narrow the claim, or add the observing assertion.
