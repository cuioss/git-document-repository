envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:48Z

# Candidate lesson: invented verb `manage-findings qgate query` during phase-3-outline

- Source: script_failure cluster `plan-marshall:manage-findings:manage-findings` (work log 2026-11-18T20:28:55Z, hash c3533b, phase 3-outline re-dispatch)
- Suggested component: plan-marshall:manage-findings (or the phase-3-outline prose that led the agent there)
- Suggested category: anti-pattern

## Observation

```
script_failure notation=plan-marshall:manage-findings:manage-findings exit_code=2 failure_kind=argparse_rejection
detail=`query` is not a registered verb for `plan-marshall:manage-findings:manage-findings qgate`:
['add', 'clear', 'list', 'resolve', 'resolve-by-rule', 'resolve-evidenced']
```

The outline agent, re-dispatched after the operator answered the outline questions, called `qgate query` instead of
`qgate list`. This is the verb-paraphrase signature of the "never invent script subcommands" rule.

## Candidate rule

Check whether the phase-3-outline (or q-gate) prose describes reading Q-Gate findings with a verb-like word such as
"query" near the `manage-findings qgate` call; if so, quote the canonical `qgate list --plan-id ... --phase ...`
invocation there so the paraphrase has nothing to latch on to.
