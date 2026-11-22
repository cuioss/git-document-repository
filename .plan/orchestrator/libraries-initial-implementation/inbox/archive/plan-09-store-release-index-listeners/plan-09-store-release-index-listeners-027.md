envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:58:02Z

component=git-core
category=bug

# Fix the two flaky tests that failed the quality gate three times

Source: plan-retrospective of plan-09-store-release-index-listeners (chat-history aspect, execute hand-backs).

## Context

The quality gate failed three times in this plan on tests the branch never touched, and each re-run on the same tree passed:
- `JGitRepositoryOperationsTest.publishLosesRace` (git-core) failed once with a `StackOverflowError` (envelope 3, TASK-9).
- `ConcurrentWritersTest.tenWritersWithOwnClones` failed twice (TASK-15 and TASK-17). The test passed, but the fixture's cleanup of the temporary remote threw `DirectoryNotEmptyException` on `objects/pack`.

## Root cause

Not analysed. The second case is most likely a cleanup race: a pack file is still being written or held when the fixture deletes the remote directory.

## Proposed action

Open a fix for both tests: find the recursion in `publishLosesRace`, and make the temporary-remote cleanup wait for or retry the deletion of `objects/pack`. Each flake costs a full gate re-run (about 2-5 minutes) and can turn CI red.

## Evidence

- aspect: chat_history_analysis - envelope 3: "failed once in git-core JGitRepositoryOperationsTest.publishLosesRace with a StackOverflowError"; round 1 and round 2 fix hand-backs: "ConcurrentWritersTest.tenWritersWithOwnClones ... DirectoryNotEmptyException on objects/pack"
