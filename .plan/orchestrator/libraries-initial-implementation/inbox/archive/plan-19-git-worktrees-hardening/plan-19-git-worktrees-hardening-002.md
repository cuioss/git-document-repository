envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:22Z

# Candidate lesson: JGit RefUpdate LOCK_FAILURE does not prove the ref moved

- Source: pr-comment finding 46db64 (CodeRabbit, PR #31, JGitWorktreeOperations.java:405), resolved fixed via TASK-11 (commit 9b4f3b8)
- Suggested component: git-worktrees (project-local; JGitWorktreeOperations worktree add rollback)
- Suggested category: bug

## Observation

The worktree-add rollback treated every `RefUpdate.Result.LOCK_FAILURE` on deleting the newly created branch as
"the branch tip advanced, keep the branch". JGit (checked against JGit v7.8.0 `RefUpdate.java`) also returns
`LOCK_FAILURE` when another process merely holds the ref lock without moving the ref. The failed addition then left
its new branch behind, and a retry of the same addition returned `ALREADY_EXISTS`.

## Resolution in this run

The rollback re-reads the ref after a `LOCK_FAILURE` and keeps the branch only when its tip actually changed;
otherwise it reports the incomplete cleanup.

## Candidate rule

Never infer a ref state change from `LOCK_FAILURE`. After a failed `RefUpdate`, re-read the ref and decide from the
observed object id; report lock contention as an incomplete cleanup (or retry), not as concurrent advancement.
