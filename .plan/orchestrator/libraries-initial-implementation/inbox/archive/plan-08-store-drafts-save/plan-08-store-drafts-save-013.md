envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:47Z

# Candidate lesson: a retained draft replayed edits that had already been saved (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash d3dd2b, PR #48, CodeRabbit inline at `ExplicitSave.java:307`
- Fix: TASK-14 (operator decision: fix it, reproduce with a test first), fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

A squash save of D1 did not record D1 in the target history. A later save of the retained draft D2 diffed from the original base, so it re-wrote D1's changes and could overwrite another writer's newer change to the same path. The existing test `keepsNewerDraft` stopped after the first save.

## Possible rule (for the orchestrator to classify)

When a save squashes draft history, track what has already been applied (for example an applied marker) so later saves diff from that point. A test of a retained draft must perform the second save and check both paths.
