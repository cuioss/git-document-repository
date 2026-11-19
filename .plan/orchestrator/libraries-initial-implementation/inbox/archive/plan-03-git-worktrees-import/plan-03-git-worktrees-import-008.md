envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:38Z

# Candidate lesson: worktreeRemove must verify repository identity before deleting a linked worktree

- Source: review-bot finding ea808f (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:353

## Defect

If an independent clean repository replaced a linked worktree at the same path, its .git entry made the old record
look non-prunable, the status check ran against the replacement and passed, and removal deleted that repository and
its local history.

## Fix in run

worktreeRemove checks that the opened Git directory is the admin directory of the matched record and refuses a
mismatch before the status check.

## Possible rule

Before a destructive operation on a path, verify the path still is the object the record refers to (identity check),
not merely that something valid exists there.
