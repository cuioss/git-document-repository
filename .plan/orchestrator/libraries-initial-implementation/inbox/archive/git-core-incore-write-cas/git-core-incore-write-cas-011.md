envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:21:09Z

# Candidate lesson: wrong flag `--number` on manage-solution-outline get-deliverable

- Source: script-failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline
- Work-log line 6a94f5, 2026-11-18T16:01:12Z (first phase-5-execute dispatch, before TASK-001 step 1)
- failure_kind: argparse_rejection, exit_code 2

## What failed

`manage-solution-outline get-deliverable --number N` was called. `--number` is not declared for get-deliverable; the declared flags are `--plan-id` and `--deliverable-number`.

## Correct form

`manage-solution-outline get-deliverable --plan-id {plan_id} --deliverable-number {N}`

## Candidate rule

Typed-ID flag paraphrase (`--number` for `--deliverable-number`) when phase-5-execute / execute-task reads the deliverable behind a task. The reading step should quote the canonical flag name from manage-solution-outline's canonical invocations.
