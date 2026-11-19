envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:56Z

# Candidate lesson: `manage-solution-outline get-deliverable --number` instead of `--deliverable-number`

- Source: script_failure cluster `plan-marshall:manage-solution-outline:manage-solution-outline` (work log 2026-11-18T20:40:03Z, hash 6a94f5, phase 5-execute first entry)
- Suggested component: plan-marshall:manage-solution-outline (or the phase-5-execute / execute-task prose that invokes it)
- Suggested category: anti-pattern

## Observation

```
script_failure notation=plan-marshall:manage-solution-outline:manage-solution-outline exit_code=2
failure_kind=argparse_rejection detail=`--number` is not declared for
`plan-marshall:manage-solution-outline:manage-solution-outline get-deliverable`: ['deliverable-number', 'plan-id']
```

The execute agent shortened the typed-ID flag `--deliverable-number` to `--number`.

## Candidate rule

Find where phase-5-execute / execute-task tells the agent to read a deliverable and make sure the canonical
`get-deliverable --plan-id ... --deliverable-number N` form is quoted verbatim there; the typed-ID flag naming rule
(argument-naming.md) is the guard this call missed.
