envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:02Z

# Candidate lesson: a main-worktree commit staged linked worktrees under the main root as gitlinks

- Source: review-bot finding dca6a7 (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:370

## Defect

worktreeAdd places linked checkouts under the main root. When that path is not ignored, JGit sees the .git file and
stages the directory as a gitlink, so a commit in the main worktree also commits a pointer to the linked checkout.

## Fix in run

A commit in the main working tree excludes the roots of registered linked worktrees from staging, with a test that
has a linked worktree present.

## Possible rule

Tests of commit-from-worktree must include the multi-worktree layout the API itself creates (linked worktree nested
under the main root), not only a lone repository.
