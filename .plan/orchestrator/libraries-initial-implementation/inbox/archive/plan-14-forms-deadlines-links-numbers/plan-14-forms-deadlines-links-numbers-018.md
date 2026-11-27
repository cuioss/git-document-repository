envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:34Z

# Run red-before-fix checks the success criteria require, or flag them unmet

- Source: plan-retrospective (request-result-alignment, chat-history-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-5-execute (and plan-marshall:execute-task)
- Category: anti-pattern
- Confidence: high

## Context

Deliverable 5's criterion said each new behaviour test fails at `bdbb5e4` and passes at head; deliverable 7's said the same for `e38e079` (and `c22700a` for the SAVED_NOT_RELEASED test). Envelope 1, envelope 3 and the fix-task envelope 6 all reported that they did not run the tests at the old commits; envelope 3 wrote reasons into the decision log instead. The tasks were closed `done` and the criteria counted as met.

## Root cause

The execute workflow has no step that checks out an old commit (or reverts the fix) to observe the red run, and closing a task does not require evidence for a criterion of this shape.

## Proposed action

Either give execute-task a sanctioned "red at base" step (run the named tests against a temporary worktree at the base commit) or require the executor to mark such a criterion unmet and surface it, instead of closing it on reasoning.

## Evidence

- request-result-alignment: gap "D5 and D7 success criteria"
- envelope 1 hand-back: "Red-at-base not checked"; envelope 3: "Old-commit check not run"; envelope 6: "Red before the fix was not observed"
