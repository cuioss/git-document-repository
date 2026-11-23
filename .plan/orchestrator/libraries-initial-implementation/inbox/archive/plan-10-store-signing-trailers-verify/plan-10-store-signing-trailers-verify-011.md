envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:36:19Z

# Candidate lesson: phase-5-execute Step 10a documents a manage-change-ledger flag that does not exist (5 recurrences)

Source: [ERROR] work-log lines from plan-marshall:phase-5-execute, `manage-change-ledger append` exit_code=2, at 08:02:17Z, 10:35:32Z, 11:09:30Z, 11:40:37Z and 14:01:00Z on 2026-11-23. Every time the cause was `--changed-paths-file` not declared, and every time a retry with `--changed-paths` succeeded. One entry names the cause directly: "--changed-paths-file documented in phase-5-execute Step 10a is not declared". These lines use the per-phase [ERROR] form rather than the execute-script `script_failure` marker, so they are not counted in signal_script_failure_clusters_count, but they are the most frequent failure of the run.

Component: plan-marshall:phase-5-execute (Step 10a, change-ledger append after a per-deliverable commit); plan-marshall:manage-change-ledger

## What happened

The workflow prose tells the agent to pass the changed-path list through a file (`--changed-paths-file`). The script's argparse declares only `--changed-paths`. Each per-deliverable commit therefore cost one rejected call and one retry, and every rejection wrote an ERROR line.

## Candidate rule

Align the doc and the script in one change: either add `--changed-paths-file` to `manage-change-ledger append`, which is preferable because a path list in a file keeps long lists out of the shell, or correct Step 10a to the declared `--changed-paths`. Add the call to the plugin-doctor `manage-invocation-invalid` coverage so that this drift fails at edit time.
