envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:09Z

# Candidate lesson: invented verb `qgate query` on manage-findings

- Source signal: script failure cluster, notation plan-marshall:manage-findings:manage-findings (argparse_rejection, exit 2), phase 3-outline re-entry, 2026-11-27T14:38:48Z
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:manage-findings (or the phase-3-outline re-entry workflow text that led to it)

## Observation

The phase-3-outline re-entry called `manage-findings qgate query`, which is not a registered verb. The registered verbs are
`add, clear, list, resolve, resolve-by-rule, resolve-evidenced`; `qgate list --plan-id ... --phase ... [--resolution pending]`
is the read. The call was recovered from within the same dispatch.

## Possible rule

Verb-paraphrase signature (read verb invented as `query`). Either the re-entry workflow names the verb ambiguously, or the agent
extrapolated; the workflow text that the re-entry follows should quote `qgate list` verbatim.
