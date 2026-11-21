envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:33Z

# Candidate lesson: a save retry re-read a moved draft instead of re-applying the same operation (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 74cbad, PR #48, CodeRabbit inline at `ExplicitSave.java:217`
- Fix: TASK-14, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

After a lease refusal, the retry re-resolved the draft reference. If the draft had moved from D1 to D2 in the meantime, the retry combined D2's content with the first attempt's expectations, which could publish unexpected edits. GDR-STORE-3 requires a retry to re-apply the same operation.

## Resolution in run

The draft id is captured once for the whole save. Each retry refreshes only the remote base and the lease.

## Possible rule (for the orchestrator to classify)

In a retry loop, capture the immutable identifier once, during validation, and use it for every attempt. Never re-resolve the mutable reference.
