envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:01Z

# Candidate lesson: two reads of an atomically published index are not one atomic read

- Source signal: review-bot finding 7f13a5 (CodeRabbit, PR #81, git-forms/.../Deadlines.java:196), resolution fixed by TASK-18 (60d80f4)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-store (StoreIndex accessor) and git-forms (Deadlines.current)

## Observation

`Deadlines.current()` read the indexed commit (`StoreIndex.commitId()`) and the entries (`StoreIndex.entries()`) in two calls,
each loading the published index separately. A refresh between them could pair the commit of one index version with the
entries of the next, so a deadline could transiently be missing. The fix added one `StoreIndex` accessor returning both from
one load; it required lifting the plan's no-git-store-change constraint at the operator's decision.

## Possible rule

A consumer that needs several values that must be consistent with each other must obtain them from one snapshot of the
published state. When designing an atomically swapped index, expose a snapshot accessor that returns everything a caller
needs together, instead of separate getters that each re-read the reference.
