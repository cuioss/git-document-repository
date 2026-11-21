envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:20Z

# Candidate lesson: maintenance and transport locks were per StoreWriter handle, not per clone (review bot, fixed in run)

- Signal: review-bot finding remediated in run, hash ce84fe, PR #48, CodeRabbit inline at `DraftExpiry.java:114`
- Fix: TASK-15 (operator decision: one shared guard per clone path), fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

`StoreWriter.open` created a new `RepositoryGuard`, and with it a new maintenance lock, on every call. Two handles on one `gitDir` did not exclude each other. GC with `pruneExpire=now` could then prune objects that another handle had inserted but not yet referenced. The specification (GDR-STORE-3 and GDR-STORE-7) says one lock per clone, but the code scoped the lock per handle.

## Possible rule (for the orchestrator to classify)

When a spec states a lock's scope ("per clone"), key the guard on that identity (the canonical clone path), and test it with two handles on the same clone.
