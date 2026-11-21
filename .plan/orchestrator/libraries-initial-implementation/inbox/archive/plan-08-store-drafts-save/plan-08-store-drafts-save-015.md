envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:55Z

# Candidate lesson: a reused clone whose remote did not match the registry was accepted (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash 56c354, PR #48, CodeRabbit inline at `StoreWriter.java:105`
- Fix: TASK-15, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

`openExistingClone` checked only that the cache path holds a bare repository. A clone of repository A at that path was accepted for repository B, and saves would then fetch from and push to A while reporting B.

## Resolution in run

`StoreWriter.open` validates the fetch and push destinations of a reused clone against `StoreRepository.remote()`.

## Possible rule (for the orchestrator to classify)

When reusing a cached resource, check its identity against the intended one, not just its type.
