envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:20Z

# Candidate lesson: invented flag `--number` on manage-solution-outline get-deliverable

**Source:** script_failure marker (work log, 2026-11-20T09:39:13Z, phase 5-execute re-entry)
**Notation:** plan-marshall:manage-solution-outline:manage-solution-outline
**Failure kind:** argparse_rejection, exit_code=2

## Observation

A phase-5 leaf called `get-deliverable --number N`. The verb declares only `--plan-id` and
`--deliverable-number`. The call failed and was retried.

## Candidate rule

`get-deliverable` takes `--deliverable-number`, not `--number`. Quote the flag from the script's canonical
invocation block; this is the verb-paraphrase recurrence signature of the never-invent-script-subcommands
rule.
