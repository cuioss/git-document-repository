envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:53:04Z

# Candidate lesson: pair-shaped token passed to `review_completeness --finished-review-bots`

- Source: script_failure cluster `plan-marshall:automatic-review:review_completeness` (work log 2026-11-18T21:44:27Z, hash 2e4daf, automatic-review step, first pass on PR #31)
- Suggested component: plan-marshall:automatic-review
- Suggested category: anti-pattern

## Observation

```
script_failure notation=plan-marshall:automatic-review:review_completeness exit_code=1
failure_kind=script_internal_failure detail=status: error / error: malformed_bot_flag /
"--finished-review-bots expects bare bot_kind tokens but received the pair-shaped token 'coderabbit:review_body'.
A bot_kind:value pair belongs on one of the pair-form flags ..."
```

The agent passed `coderabbit:review_body` (a `bot_kind:value` pair) to the bare-form flag `--finished-review-bots`.
The script's guard caught it and the step still completed `done`, but only after a retry.

## Candidate rule

In the automatic-review workflow, the place that assembles the `review_completeness` call should state, next to the
call, which flags take bare `bot_kind` tokens and which take `bot_kind:value` pairs, with one example of each, so the
agent does not have to infer the form from the observed bot evidence.
