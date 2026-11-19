envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:29:34Z

# manage-change-ledger append rejects --changed-paths-file

**Source signal:** work-log `[ERROR]` line in 5-execute (2026-11-19T17:02:06Z)

**Component:** plan-marshall:manage-change-ledger (verb surface) and the workflow docs that name its flags

## What happened

After the TASK-11 per-deliverable commit, phase-5-execute called the change-ledger append verb and got an argparse rejection:

```text
[ERROR] (plan-marshall:phase-5-execute) Script plan-marshall:manage-change-ledger:manage-change-ledger append failed: exit_code=2, args=--changed-paths-file rejected (installed verb declares --changed-paths)
```

The installed verb declares `--changed-paths` only. The caller used `--changed-paths-file`, a file form for a list of paths that can get long. This failure is not in the dispatcher's script-failure cluster count. It was logged with the plain `failed:` form, not the `script_failure` marker, so the signal scan does not see it.

## Candidate rule

Make the workflow docs and the verb agree. Either add `--changed-paths-file` to `append` (a file form fits a long path list and the project's no-multi-line-shell rule), or fix every workflow-doc reference to the declared `--changed-paths`. Also check that this error line form is counted by `signal-scan`.
