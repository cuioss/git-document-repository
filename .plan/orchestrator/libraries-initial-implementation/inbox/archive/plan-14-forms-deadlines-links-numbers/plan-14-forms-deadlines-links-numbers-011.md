envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:05Z

# Candidate lesson: a failed result's null value was read as a domain value

- Source signal: review-bot finding d9a202 (CodeRabbit, PR #81, git-forms/.../Deadlines.java:258), resolution fixed by TASK-17 (f36083d)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-forms (Deadlines.entered, FormsResult handling)

## Observation

The history walk of `Deadlines.entered` compared the value of `TransitionStep.readState()` with the current state without
checking the result. For an earlier file version whose `state` is not a string, `readState` returns a passed-on `MALFORMED`
with no value; the null was unequal to the current state, so the walk stopped as if the state had changed and the deadline
could be computed too late. The specification says such a document ends the call with the passed-on `MALFORMED`.

## Possible rule

With results-instead-of-exceptions, never read the value of a result before checking `isOk()`; a failed result must be
propagated, not interpreted. Distinguish the normal stopping conditions (missing file, different state) from a read failure.
