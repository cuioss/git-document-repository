envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:54Z

# Candidate lesson: pruning a stale worktree record must delete only its admin files, never the reused path

- Source: review-bot finding c20f94 (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:358

## Defect

A manually removed worktree whose path was reused for a directory without .git was marked prunable, which skipped the
status check, but removal still recursively deleted the replacement directory.

## Fix in run

A prunable record is removed through its administration directory only; recursive deletion is reserved for a
verified clean linked worktree.

## Possible rule

The deletion scope must follow the verified state: an unverified (prunable) record may only lose metadata the library
owns; user content is deleted only after the checks that justify it.
