envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:29Z

# Candidate lesson: LOCK_FAILURE was taken as proof that a concurrent draft exists (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 634c6b, PR #48, CodeRabbit inline at `DraftReferences.java:180`
- Fix: TASK-13, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

JGit 7.8.0 `RefUpdate` also returns `LOCK_FAILURE` when it simply cannot acquire the ref lock. The code read `LOCK_FAILURE` as "another writer created the draft" and continued. If no ref had been created, it could publish a root draft that left out the target branch's files.

## Resolution in run

The draft is continued only when the reference actually exists. Otherwise base selection is retried.

## Possible rule (for the orchestrator to classify)

Check a guard's predicate against the scenario it exists for. A JGit result code that has several causes is not evidence of one particular cause. Confirm the state you infer before acting on it.
