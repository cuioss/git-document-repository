envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:29:45Z

# manage-findings list rejects --limit, and the same call was repeated three times in one run

**Source signal:** script failure cluster `plan-marshall:manage-findings:manage-findings` (3 `script_failure` rows, one hash 6ef3b9)

**Component:** plan-marshall:manage-findings (list verb) and its callers in phase-6-finalize

## What happened

The same argparse rejection was logged three times in 6-finalize, by the same caller each time:

- 2026-11-19T18:06:57Z: after sonar-roundtrip, when verification-feedback was dispatched
- 2026-11-19T18:51:27Z: during automatic-review iteration 2
- 2026-11-19T19:26:52Z: after branch-cleanup, right before this lessons-capture dispatch (most likely the dispatcher's Signal Gate query for remediated pr-comments)

```text
script_failure notation=plan-marshall:manage-findings:manage-findings exit_code=2 failure_kind=argparse_rejection detail=`--limit` is not declared for `plan-marshall:manage-findings:manage-findings list`
```

`list` declares no `--limit`. Its flags are `any-checkout, author, bot-kind, component, file-pattern, include-qgate, kind, plan-id, preference-admissible, promoted, resolution, type`. All three callers recovered by retrying without the flag. Repeating the same call shows the wrong flag comes from a prompt or doc template, not a one-off slip. Several workflow sites probably carry `list ... --limit N` for a count-only query.

## Candidate rule

Count-only finding queries should not pass `--limit` to `manage-findings list`. Either add a declared `--limit` (or a count-only verb), since `filtered_count` is all these callers need, or find the workflow text that carries `--limit` and remove it. The fact that the same hash 6ef3b9 recurred suggests one shared source.
