envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:11Z

# Candidate lesson: a gc.pid race left a skipped GC reported as success (review bot second round, fixed in run)

- Signal: review-bot finding remediated in run, hash b96049, PR #48, CodeRabbit inline at `DraftExpiry.java:223` (second review round, on fix commit 9f3e622d)
- Fix: TASK-18, fix commit 457160b2
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The first-round fix added a `gc.pid` pre-check. Another process could still create `gc.pid` after that check. In that case JGit 7.8.0 `GC.doGc()` returns an empty result without any error when `PidLock.lock()` fails, and the expiry reported OK again. The pre-check was a time-of-check/time-of-use (TOCTOU) guard, not a mechanism.

## Resolution in run

A progress monitor records whether JGit called `ProgressMonitor.start`, which it does only after it takes the lock. A collection that never started is reported as CONFLICT.

## Possible rule (for the orchestrator to classify)

Detect a skipped operation from evidence that the operation actually ran, not from a pre-check of its preconditions.
