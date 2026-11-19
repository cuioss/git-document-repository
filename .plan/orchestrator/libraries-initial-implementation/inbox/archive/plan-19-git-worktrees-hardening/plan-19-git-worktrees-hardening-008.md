envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:53:14Z

# Candidate lesson: phase-5 Step 10a documents `manage-change-ledger append --changed-paths-file`, but the installed script rejects it

- Source: work log ERROR lines 2026-11-18T20:47:04Z (hash a9f44c, after the TASK-2 per-deliverable commit) and 2026-11-18T21:55:26Z (hash f607e9, after the TASK-11 commit). Not counted in the script-failure signal (logged as a phase-5 `[ERROR] Script ... failed` line, not as a `script_failure` marker), but it recurred twice.
- Suggested component: plan-marshall:phase-5-execute (doc) and/or plan-marshall:manage-change-ledger (script)
- Suggested category: bug

## Observation

```
[ERROR] (plan-marshall:phase-5-execute) Script plan-marshall:manage-change-ledger:manage-change-ledger append failed:
exit_code=2, args=--changed-paths-file (undeclared; documented in phase-5 Step 10a) - retrying with --changed-paths
[ERROR] (plan-marshall:phase-5-execute) Script plan-marshall:manage-change-ledger:manage-change-ledger append failed:
exit_code=2, args=--changed-paths-file (flag not declared by the worktree executor)
```

Both times the executing agent followed the documented Step 10a form, hit an argparse rejection, and fell back to
`--changed-paths`. The run was installed plan-marshall 0.1.2157/0.1.2159; the doc and the script disagree there.

## Candidate rule

Either add `--changed-paths-file` to `manage-change-ledger append` (the file form is the shell-safe one for long path
lists), or change phase-5 Step 10a to the declared `--changed-paths` form. A canonical-invocation check that walks the
script's `--help` should cover the Step 10a call so the drift cannot recur.
