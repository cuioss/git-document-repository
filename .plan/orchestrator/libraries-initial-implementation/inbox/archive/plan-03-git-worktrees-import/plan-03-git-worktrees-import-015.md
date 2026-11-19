envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:37Z

# Candidate lesson: review-body minor findings on git-worktrees (start point, rollback, relative gitdir, weak tests)

- Source: review-bot finding 21a9fe (coderabbitai, PR #30 review body, 7 minor + 1 nitpick), resolution fixed by TASK-8 and TASK-9 (a86a1ee)
- Files: WorktreeOperations.java, JGitWorktreeOperations.java, LinkedWorktrees.java, WorktreeOperationsRecordsTest.java,
  LinkedWorktreesTest.java, GitWorktreeListCompatibilityTest.java, git-worktrees/doc/LogMessages.adoc

## Adopted (fixed)

- Unknown start point with an existing branch returned OK instead of NOT_FOUND; reject UNKNOWN before dispatch (TASK-8).
- A failed initialization write in LinkedWorktrees.create left a locked admin record; roll back the claimed admin dir (TASK-8).
- A relative `gitdir` entry was resolved against the process cwd instead of the admin directory, giving wrong path and
  false prunable (TASK-8).
- Defensive-copy test could add a value already in the set, so it proved nothing; use a value guaranteed new (TASK-9).
- Concurrency test could give two workers the same root, hiding a double write; distinct parents per worker (TASK-9).
- Subprocess helpers read stdout before the timed wait, so the timeout could never fire; one bounded runner that
  kills the child on timeout (TASK-9).

## Not adopted

Generating LogMessages.adoc from the LogRecord holder (a new build mechanism, outside the plan), and replacing two
AddCommand calls with setAll(true) (not verified against JGit 7.8.0).

## Possible rules

- Resolve paths read from Git metadata files against the directory that holds them, never against the process cwd.
- A test guard (timeout, defensive copy, concurrency) must be checked against the scenario it exists for: a test
  whose input can make the guarded behaviour a no-op passes vacuously.
