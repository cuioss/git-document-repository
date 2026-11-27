envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:57Z

# Candidate lesson: a second code path ran consumer callbacks outside the store's isolation

- Source signal: review-bot finding fefc4d (CodeRabbit, PR #81, git-forms/.../CatalogResolver.java:33), resolution fixed by TASK-16 (90da5fd)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-forms (UnchangedSave, TransitionSave, isolation of consumer callbacks)

## Observation

For a save that leaves the draft unchanged, `UnchangedSave.verifySaved` ran the transition's checks outside the store's step
isolation. With a catalog link present the checks called `CatalogResolver.resolve`, and a `RuntimeException` from it escaped
to the caller of `DocumentTransitions.perform` instead of ending the transition with `GitOutcome.FAILED` as on the changed-save path.
This surfaced only in the second review round, after the Javadoc fix of f1d209 described the isolation.

## Possible rule

When a consumer-supplied callback is invoked, enumerate every call site, not only the main path: each one needs the same
isolation (catch `RuntimeException`, return the failed outcome naming only the exception class). A test per call site
(changed and unchanged save) pins it.
