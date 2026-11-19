envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:10Z

# Candidate lesson: JGit runs post-commit after moving the branch and swallows the RefusingFS refusal; linked commits also hit the main HEAD reflog

- Source: review-bot finding acc182 (coderabbitai, PR #30, Major, Data Integrity; two comments in one thread), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:378
- JGit version cited by the reviewer: 7.8.0 (CommitCommand)

## Defect

1. With only an executable post-commit hook present, JGit updates the branch before calling the hook, catches the
   RefusingFS exception and returns the commit, so the API returned OK instead of REFUSED_BY_POLICY.
2. With reflogs enabled, the file backend resolves logs/HEAD under the common directory, so a commit in a linked
   worktree is recorded in the main worktree's HEAD reflog.

## Fix in run

A present post-commit hook ends REFUSED_BY_POLICY before the branch moves (tested on outcome and branch tip); the
HEAD reflog claim was to be checked against JGit 7.8.0 CommitCommand and fixed if it holds.

## Possible rule

RefusingFS is an execution guard, not a policy check: refuse hook-bearing operations up front by checking for the
hooks the command path uses, and verify against JGit source which hooks fire after a ref update. Test reflogs of
both worktrees, not only branch tips.
