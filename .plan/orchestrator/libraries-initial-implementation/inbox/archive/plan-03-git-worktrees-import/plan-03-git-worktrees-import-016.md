envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:46Z

# Candidate lesson: phase-5-execute Step 10a documents manage-change-ledger append --changed-paths-file, which the script rejects

- Source: two work-log [ERROR] lines (2026-11-18T15:21:32Z and 16:44:54Z), phase 5-execute.
  Not counted by signal-scan (the lines do not use the `script_failure notation=` marker), forwarded for completeness.
- Notation: plan-marshall:manage-change-ledger:manage-change-ledger append

## What happened

Both per-deliverable ledger appends in this plan failed with exit_code=2 because `--changed-paths-file` is
documented in phase-5-execute Step 10a but not declared by the script's parser. Each time the call was retried
with `--changed-paths` and succeeded.

## Possible rule

Align phase-5-execute Step 10a with the manage-change-ledger argparse surface (or add the file flag to the script);
the recurrence is the documented-flag-not-declared argparse-rejection class.
