envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:47Z

# Candidate lesson: method-scope cleanup check could not tell method cleanup from class cleanup

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding ec57c8, git-testing/src/test/java/de/cuioss/git/document/testing/PerClassLifecycleTest.java:150). Fixed by TASK-11, commit 3ffe210.

## Finding

The test after the order-3 method checked only that the previous method's local repository was gone. If `@Remote` had wrongly registered its method fixture in the class store, the test still passed, because the remote was checked only in the outer `@AfterAll`, after the class store had closed anyway.

## Candidate rule

To prove a resource is released at a given scope boundary, check it before the next wider boundary closes. Save every method-fixture path and assert all of them are gone in the next test, not at class teardown.
