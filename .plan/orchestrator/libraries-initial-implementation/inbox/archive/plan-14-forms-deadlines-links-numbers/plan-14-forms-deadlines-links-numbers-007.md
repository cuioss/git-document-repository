envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:50Z

# Candidate lesson: Javadoc named the wrong outcome for an isolated callback exception

- Source signal: review-bot finding f1d209 (CodeRabbit, PR #81, git-forms/.../CatalogResolver.java:33), resolution fixed (5487c45)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-forms (CatalogResolver Javadoc), store isolation rule in StoreEvents

## Observation

The Javadoc of `CatalogResolver.resolve` said a `RuntimeException` "ends the save as a refused step of the store". The store
actually catches it and ends the save with the passed-on `GitOutcome.FAILED`, not retried, with a detail naming only the
exception class.

## Possible rule

When documenting what happens to a consumer callback's exception, quote the outcome the isolation code actually returns
(check `StoreEvents` and the save path) rather than paraphrasing; "refused" and "failed" are distinct closed outcomes here.
