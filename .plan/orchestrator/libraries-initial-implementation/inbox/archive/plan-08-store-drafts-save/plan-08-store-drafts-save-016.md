envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:00Z

# Candidate lesson: a concurrency test could hang instead of failing (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash e63eb6, PR #48, CodeRabbit inline at `git-store/src/test/java/de/cuioss/git/document/store/ConcurrentWritersTest.java:185`
- Fix: TASK-16, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

`Future.get` timed out, but try-with-resources then called `ExecutorService.close()`, which waits for the blocked worker. A hung worker therefore hung the test instead of failing it.

## Resolution in run

The test uses one deadline for the scenario, cancels outstanding tasks, and tears the executor down with a bound.

## Possible rule (for the orchestrator to classify)

Concurrency tests must not rely on `ExecutorService.close()` in try-with-resources on Java 21 or later. Use a single deadline, cancellation, and `shutdownNow` with `awaitTermination` in teardown.
