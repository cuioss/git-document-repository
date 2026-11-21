envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:42Z

# Candidate lesson: a stale local ref was used as the lease after the remote branch was deleted (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 55b1c6, PR #48, CodeRabbit inline at `ExplicitSave.java:292`
- Fix: TASK-14, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

JGit fetch does not prune by default, so after a remote deleted the target branch, the clone kept the stale local ref. The save used it as the lease and was rejected on every retry instead of taking the new-branch path.

## Resolution in run

Target presence is derived from the successful fetch.

## Possible rule (for the orchestrator to classify)

Derive remote state from the fetch result, not from local mirror refs. With JGit, assume no pruning unless it is requested explicitly.
