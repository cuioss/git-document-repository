envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:31Z

# Candidate lesson: a failed new-branch worktree add left the created branch behind, blocking retry

- Source: review-bot finding 3b00bc (coderabbitai, PR #30, Major, Data Integrity), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:235

## Defect

When checkout failed (e.g. a smudge filter refused), cleanup removed the worktree and admin directory but left the
branch the attempt had created, so the retry returned ALREADY_EXISTS.

## Fix in run

A branch created by a failed add is rolled back only while it still points at the commit that attempt created; a
pre-existing or concurrently advanced branch is not touched.

## Possible rule

Rollback must cover every side effect the attempt created (refs included), guarded by an ownership check so it never
deletes state the attempt did not create.
