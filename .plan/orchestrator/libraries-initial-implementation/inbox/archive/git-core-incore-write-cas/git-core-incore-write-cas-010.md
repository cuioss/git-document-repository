envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:21:01Z

# Candidate lesson: invented verb `qgate query` on manage-findings

- Source: script-failure cluster, notation plan-marshall:manage-findings:manage-findings
- Work-log line c3533b, 2026-11-18T15:03:00Z (after the 3-outline q-gate-validation dispatch returned, in the orchestrator context)
- failure_kind: argparse_rejection, exit_code 2

## What failed

`manage-findings qgate query ...` was called. `query` is not a registered verb of `qgate`; the registered verbs are add, clear, list, resolve, resolve-by-rule, resolve-evidenced.

## Correct form

`manage-findings qgate list --plan-id {plan_id} --phase {phase}` (optionally with its filters) reads the phase's Q-Gate findings.

## Candidate rule

Verb-paraphrase recurrence (query for list) on the qgate sub-noun, in the orchestrator step that reads Q-Gate findings after q-gate-validation. The orchestrator-side reader should quote `qgate list` verbatim from the manage-findings canonical invocations.
