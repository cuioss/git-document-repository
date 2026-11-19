envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:39Z

# Candidate lesson: hold a branch reservation until checkout and rollback have finished

- Source: pr-comment finding f1ecca (CodeRabbit review body, outside-diff comment at JGitWorktreeOperations.java:283), resolved fixed via TASK-11 (commit 9b4f3b8). The CodeRabbit walkthrough comment 28a132 (accepted, summary only) repeats this point and adds no separate candidate.
- Suggested component: git-worktrees (project-local; worktree add reservation lifecycle)
- Suggested category: bug

## Observation

Worktree addition released its branch reservation before checkout. If addition A's checkout failed, its rollback
removed A's registration before deleting the branch A had created. In that window addition B could reserve the same
branch, register a worktree at the same commit and return `OK`; A's rollback then deleted B's branch, because the
expected commit id still matched and JGit's ref deletion does not protect another linked worktree's `HEAD`.

## Resolution in this run

The reservation is released only after a successful checkout; during rollback the created branch is deleted before
the reservation is released.

## Candidate rule

A mutual-exclusion reservation must cover the whole critical section including its compensating rollback: release it
last, after success or after the rollback has undone everything the holder created. An expected-old-id check on ref
deletion is not ownership; it cannot tell the holder's branch from an identical branch adopted by someone else.
