envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:24Z

# Candidate lesson: an existing branch must be reserved across the occupancy check and worktree registration

- Source: review-bot finding 5a58ad (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:202

## Defect

Two adds for the same unused branch could both pass the checkedOut check before either published its linked HEAD,
so the same branch ended up checked out in two worktrees. Atomic admin-directory naming does not reserve the branch.

## Fix in run

The branch is reserved across the occupancy check and the registration, and released on failure so a retry works.

## Possible rule

Every invariant the API guarantees (one checkout per branch, one admin dir per worktree) needs its own atomic claim;
an atomic claim on one resource does not protect another.
