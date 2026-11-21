envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:20Z

# Candidate lesson: the pruning fetch used the clone's stored refspecs and could delete drafts (review bot second round, fixed in run)

- Signal: review-bot finding remediated in run, hash 876947, PR #48, CodeRabbit inline at `ExplicitSave.java:249` (second review round)
- Fix: TASK-20, fix commit 457160b2
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The first-round fix enabled `prune(true)` on the save fetch, with an empty refspec list. The fetch then used whatever fetch refspecs the reused clone had stored. A broader refspec such as `+refs/*:refs/*` would make the pruning fetch delete `refs/drafts/` and `refs/applied/`, including unsaved drafts. The reuse check compared only the remote URL.

## Resolution in run

The pruning fetch is restricted to the mirrored namespaces. A regression test covers a clone with a broader stored refspec.

## Possible rule (for the orchestrator to classify)

A destructive option (prune) must always come with explicit scoping (refspecs). Never let it inherit scope from mutable external configuration. A fix for one review finding can open the next, so review each fix's blast radius.
