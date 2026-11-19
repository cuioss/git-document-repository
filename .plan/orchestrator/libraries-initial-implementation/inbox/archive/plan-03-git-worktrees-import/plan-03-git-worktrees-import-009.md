envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:47Z

# Candidate lesson: worktree removal has a check-then-delete window that cannot be closed in code; state the caller contract

- Source: review-bot finding 2a5747 (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:353-358

## Defect

worktreeRemove checked cleanliness and then recursively deleted the tree; a file written between the two was lost
without the WORKTREE_DIRTY guard noticing.

## Fix in run

The clean check and delete cannot be made atomic, so the worktreeRemove contract and doc/specification/git-worktrees.adoc
now state that the caller guarantees exclusive use of the worktree through removal.

## Possible rule

When a guarantee cannot be enforced by the library (TOCTOU on a filesystem the library does not own), make it an
explicit caller precondition in Javadoc and the specification instead of leaving the guard to imply more than it gives.
