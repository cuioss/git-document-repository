envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:37Z

# Candidate lesson: an accepted push was reported as failed when the local ref update threw (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash f23eb7, PR #48, CodeRabbit inline at `ExplicitSave.java:270`
- Fix: TASK-14, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

If the remote accepted the push and `moveLocal()` then threw `IOException`, the outer catch returned `NotSaved(FAILED)` and skipped draft cleanup, although the remote already held the commit. A returned `LOCK_FAILURE` on the same step was handled as `Saved`, so the two failure paths were inconsistent.

## Possible rule (for the orchestrator to classify)

Once an irreversible remote effect has happened, every later local failure must be reported without contradicting that effect. Handle exceptions after the point of no return separately from those before it.
