envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:19:52Z

# Candidate lesson: a retry-between-attempts test needs a declared deterministic seam

- Source: Q-Gate finding ed4406 (phase 3-outline), resolution taken_into_account
- Component: git-core ReferenceUpdaterTest (compareAndDelete, GDR-CORE-5)

## Observation

The outline required a test in which a held refs/...lock file makes compareAndDelete fail with LOCK_FAILURE and the retry with the same expected id succeeds once the lock is released. compareAndDelete(repository, refName, expectedOldId, attempts) offers no callback between attempts (unlike the TreeOperation of compareAndSwap), so releasing the lock between attempts needed either a timed background thread (non-deterministic) or a seam the outline did not name.

## Resolution in this plan

Folded into TASK-006: a test-local Repository double whose exactRef re-read after the first LOCK_FAILURE deletes the held lock file (getRefDatabase override if exactRef is final in JGit 7.8.0); no sleep, no thread, no production hook.

## Candidate rule

When an outline asks for a test of retry behaviour across attempts, name the deterministic seam (a re-read hook in a test double) in the deliverable; never leave it to a timed thread.
