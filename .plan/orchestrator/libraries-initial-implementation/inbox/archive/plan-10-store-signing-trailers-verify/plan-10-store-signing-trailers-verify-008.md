envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:35:44Z

# Candidate lesson: manage-findings argparse rejections from invented flags

Source: script-failure cluster `plan-marshall:manage-findings:manage-findings` (failure_kind=argparse_rejection), two occurrences:

- 2026-11-23T07:38:54Z in q-gate-validation (phase 3-outline): `qgate add` rejected (exit 2); the usage text shows that `--phase`, `--source`, `--type`, `--title` and `--detail` are required. The exact missing or invented argument was cut off in the log.
- 2026-11-23T13:42:41Z in automatic-review (phase 6-finalize): `list --limit` rejected; `--limit` is not declared for `manage-findings list` (declared: any-checkout, author, bot-kind, component, file-pattern, include-qgate, kind, plan-id, preference-admissible, promoted, resolution, type).

Component: plan-marshall:manage-findings (callers: plan-marshall:plan-marshall/workflow/q-gate-validation.md, plan-marshall:automatic-review)

## Candidate rule

This is the "never invent script subcommands" recurrence class (persona-plan-marshall-agent): an agent added a plausible flag (`--limit`) that the verb does not declare. Either the workflow docs that call `manage-findings list` show a pagination flag that does not exist, or agents expect one. Check both workflow bodies for a documented `--limit` on `manage-findings list`. If none is documented, the defect is agent-side; consider adding `--limit` to `list`, since agents reach for it on a 60-record store whose output exceeds the tool limit.
