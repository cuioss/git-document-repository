envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:29Z

component=plan-marshall:phase-5-execute
category=bug

# phase-5-execute documents manage-change-ledger append --changed-paths-file, which the installed executor does not declare

Source: two `[ERROR]` work-log lines (2026-11-21T13:59:19Z after the TASK-2 commit, 2026-11-21T14:50:39Z after the TASK-9 commit).

## What happened

The per-deliverable commit step called `manage-change-ledger append --changed-paths-file ...` as `phase-5-execute` (0.1.2181) documents it. The installed executor rejected the flag with exit_code=2. The ledger rows for those commits had to be recorded another way. Separately, one TASK-4 ledger row was first written with `commit_sha=HEAD` instead of the resolved SHA, and a corrected row followed it.

## Rule

A workflow doc and the installed executor mapping can drift between bundle versions. Before a plan starts, a check should confirm that every flag the loaded workflow docs pass to a script is declared by that script. At minimum, `append` should reject a non-hex `commit_sha` such as `HEAD` instead of storing it.

## Note

These lines use the `[ERROR] (plan-marshall:phase-5-execute) Script ... failed` form, not the `script_failure notation=` form, so `signal-scan` did not count them as a failure cluster.
