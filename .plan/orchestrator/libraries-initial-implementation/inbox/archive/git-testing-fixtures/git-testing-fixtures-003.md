envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:09Z

# Candidate lesson: recursive temp-directory delete must tolerate JGit lock files that vanish mid-walk

- Source signal: Q-Gate finding 6f4740 (phase 5-execute, test-failure, severity error)
- Resolution in run: fixed by TASK-6, commit 9446093 on PR #23

## What happened

`TemporaryDirectories.delete` (git-testing) used `Files.walkFileTree`. It visited `gc.log.lock` under `/tmp/git-testing-*`. JGit removed that lock file while the walk was running, so the walk threw `NoSuchFileException`. `LocalRepositoryExtension.TemporaryRepository.close` then failed. JUnit reported "Failed to close extension context", and `CombinedFixturesTest.everyDirectoryIsDeleted` found the directory still present. Build `verify -pl git-testing -am` had 73 passed and 2 failed.

## Candidate rule

Fixture cleanup that walks a JGit repository directory must treat `NoSuchFileException` as already deleted, in both `visitFile` and `visitFileFailed`. Every other failure must still propagate. JGit creates and removes transient lock files (`*.lock`, `gc.log.lock`) on its own schedule. Relevant to every later module that builds file-backed JGit fixtures (git-core, git-worktrees).

Classification is deferred to the orchestrator.
