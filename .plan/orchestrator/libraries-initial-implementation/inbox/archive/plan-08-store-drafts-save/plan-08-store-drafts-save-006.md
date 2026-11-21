envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:16Z

# Candidate lesson: stale-reference deletion could interleave with a draft write (review bot, fixed in run)

- Signal: review-bot finding remediated in run (pr-comment, resolution fixed), hash 683f91, PR #48, CodeRabbit inline at `git-store/src/main/java/de/cuioss/git/document/store/DraftExpiry.java:110`
- Fix: TASK-13, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

Draft expiry deleted stale references under the same shared lock that draft writes take. A write could see an existing draft, then expiry deleted it before the write's compare-and-swap. The write then created a root commit with only the new edits and reported OK, so the untouched draft content was lost. The local build was green: no test paused a write between its existence check and its compare-and-swap.

## Resolution in run

Stale-reference deletion no longer interleaves with a draft write. A regression test pauses a draft update between the existence check and the compare-and-swap.

## Possible rule (for the orchestrator to classify)

For every operation that deletes references, name the readers that hold the shared lock and could be between check and CAS. Write a test that pauses each of them at that point.
