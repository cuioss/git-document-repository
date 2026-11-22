envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:28Z

component=plan-marshall:manage-solution-outline
category=anti-pattern

# get-deliverable was called with --number instead of --deliverable-number

Source: script-failure cluster `plan-marshall:manage-solution-outline:manage-solution-outline` (1 `[ERROR] script_failure` line, 2026-11-21T14:15:19Z, failure_kind=argparse_rejection).

## What happened

At phase 5-execute re-entry, `manage-solution-outline get-deliverable --number N` was rejected. The declared flags are `--plan-id` and `--deliverable-number`.

## Rule

Typed-ID flags carry the entity name (`--deliverable-number`, `--task-number`, `--lesson-id`). A bare `--number` or `--id` is never a declared flag on a manage-* script. See `persona-plan-marshall-agent` argument-naming.md.
