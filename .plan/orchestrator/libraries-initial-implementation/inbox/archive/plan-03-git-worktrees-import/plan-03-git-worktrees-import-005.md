envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:17Z

# Candidate lesson: worktree add must claim its target directory exclusively before writing .git

- Source: review-bot finding b131ff (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:151

## Defect

Two concurrent adds could both pass the empty-target check; the second accepted the existing directory, replaced
the first checkout's .git file, and its failure cleanup then deleted the first checkout.

## Fix in run

The target is claimed exclusively (including caller-supplied empty directories) and cleanup after a failed add is
limited to what that attempt created.

## Possible rule

A check-then-create on a filesystem target is a race; claim it atomically and scope rollback to what the attempt
itself created. (Same family as the atomic admin-directory claim the owner approved in this plan.)
