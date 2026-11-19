envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:25Z

# Candidate lesson: worktree listing failed on an admin entry still being initialized

- Source: review-bot finding d2a7a8 (coderabbitai, PR #30, Major, Stability & Availability), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/LinkedWorktrees.java:99

## Defect

If an add paused after writing `locked` but before writing `gitdir`, LinkedWorktrees.list tried to read the missing
file; worktreeList returned FAILED and worktreeRemove of an unrelated worktree could not proceed.

## Fix in run

An administration entry still being initialized no longer makes worktreeList or the removal of another worktree
fail, and fully initialized locked worktrees stay listed.

## Possible rule

Readers of a multi-file on-disk record written by concurrent writers must tolerate a partially published record
(skip it, distinguishable from a complete locked one) instead of failing the whole listing.
