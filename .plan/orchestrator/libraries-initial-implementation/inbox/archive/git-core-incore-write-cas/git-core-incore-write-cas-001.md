envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:19:43Z

# Candidate lesson: threaded compare-and-swap tests need a fixture-aware assertion

- Source: Q-Gate finding 1bb644 (phase 3-outline), resolution taken_into_account
- Component: git-core ReferenceUpdaterTest (compare-and-swap, GDR-CORE-5)

## Observation

The outline required a threaded compare-and-swap test (N threads, attempts >= N, every thread succeeds) on both repository fixtures. On the local (file) fixture JGit 7.8.0 RefDirectory reports LOCK_FAILURE also while another thread merely holds the loose-ref lock file, so one thread can lose several attempts to a single competing success. "attempts >= N implies all succeed" holds only on the in-memory (DFS) reference database, which compares atomically and fails only on a real advance.

## Resolution in this plan

Folded into TASK-004: the local fixture asserts the invariant (every OK commit is in the history, every non-OK result is CONFLICT, the final reference equals the history head); the all-succeed bound is asserted only on the in-memory fixture.

## Candidate rule

When a concurrency test runs on both the local and the in-memory fixture, assert the safety invariant on the local fixture rather than a liveness bound derived from attempt counts; the lock-file reference database turns lock contention into LOCK_FAILURE.
