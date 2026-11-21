envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:25Z

# Candidate lesson: a draft that disappears before the CAS let an update write a root commit (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 23af0f, PR #48, CodeRabbit inline at `DraftReferences.java:113`
- Fix: TASK-13, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

If expiry deleted a draft after `createIfMissing` had found it, the compare-and-swap read an absent ref and wrote a root commit. That commit carried no branch fallback parent, so deletions relative to `main` were lost at the next save.

## Resolution in run

An update that sees an absent draft re-selects the branch fallback parent instead of writing a root commit.

## Possible rule (for the orchestrator to classify)

Every CAS on a reference whose absence has a special meaning must re-derive that meaning at the CAS, not take it from an earlier read.
