envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:28:39Z

# A fix that falls back on JGit internals for symbolic references drew a new review round

**Source signal:** automated review (coderabbit pr-comment findings remediated in this run)

**Component:** git-core `ReferenceUpdater` (project code); plan-marshall:phase-6-finalize review loop

## What happened

Round 1 (head 4733bb1): coderabbit finding 33f0f3. On the in-memory DFS backend, a symbolic reference can never be updated or deleted by `compareAndSwapTree` / `compareAndDelete`. The cause, in JGit 7.8.0: `DfsRefUpdate.tryLock(false)` sets the old id to null, so the expected-id check compares the resolved id with zero, and every attempt ends in `LOCK_FAILURE`. TASK-13 fixed this with an atomic `BatchRefUpdate` fallback for symbolic references (commit cf833c4).

Round 2 (head cf833c4) then raised three new findings, all against that fallback code:

- 70bb0a (Major): a dangling symbolic reference with `expected == null` can overwrite notes that another writer just wrote. The DFS backend still reports a null old id, so the zero expected id matches.
- 56179b: `ReftableBatchRefUpdate.execute` reports an IOException as `LOCK_FAILURE` with the message "io error". The boolean batch result reported that as contention, so the call retried and returned CONFLICT instead of FAILED.
- 44009a (review body, Major): `ReftableBatchRefUpdate.matchOld` compares a symbolic command only with its immediate target. A retargeted middle link of `alias -> middle -> base` went unseen.

All three were fixed in TASK-014 (1540c7a). That took a second loop-back to 5-execute and a third review round before the merge.

## Candidate rule

When a fix has to go around a JGit backend quirk with a lower-level primitive (`BatchRefUpdate`, reftable commands), read the predicates that primitive actually checks before the first push. Write each concurrent-writer case down as a test in the same task: dangling target, chained symbolic links, and I/O failure versus lost race. The reviewer found these cases by reading `matchOld` and `execute`. Each case left untested cost one more review round.
