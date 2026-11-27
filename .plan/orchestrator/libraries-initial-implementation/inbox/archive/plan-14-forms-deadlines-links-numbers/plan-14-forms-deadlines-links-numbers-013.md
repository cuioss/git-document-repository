envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:12Z

# Candidate lesson: `get-deliverable --number` instead of `--deliverable-number` (recurred twice)

- Source signal: script failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline (argparse_rejection, exit 2), phase 5-execute, 2026-11-27T15:28:36Z and 15:56:22Z
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:manage-solution-outline / plan-marshall:phase-5-execute

## Observation

Two separate phase-5-execute dispatches called `manage-solution-outline get-deliverable --number N`. The declared flags are
`--plan-id` and `--deliverable-number`. The same mistake recurring across two fresh dispatches suggests the phase-5-execute
(or execute-task) workflow text shows or implies `--number`, rather than a one-off slip.

## Possible rule

Check the phase-5-execute / execute-task workflow docs for a `get-deliverable` example and make it quote `--deliverable-number`
verbatim; consider an argparse alias if `--number` is a natural reading.
