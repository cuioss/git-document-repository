envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:16Z

# Candidate lesson: the applied-draft marker was read outside same-draft serialization (review bot second round, fixed in run)

- Signal: review-bot finding remediated in run, hash 5ad192, PR #48, CodeRabbit inline at `ExplicitSave.java:225` (second review round)
- Fix: TASK-19, fix commit 457160b2
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The first-round applied-marker fix read the draft tip and the marker before any per-draft ordering existed. A second save of the same draft, running between the first save's push and its settlement, could therefore replay a stale snapshot. Serializing only the transport step did not help.

## Resolution in run

A per-draft lock is taken before the snapshot read and released after settlement. One residual case stays documented as out of scope: a failed applied-marker write.

## Possible rule (for the orchestrator to classify)

A serialization lock must cover the reads that the protected decision depends on, not only the write. Second-round review findings on concurrency fixes are common, so plan for a second review pass.
