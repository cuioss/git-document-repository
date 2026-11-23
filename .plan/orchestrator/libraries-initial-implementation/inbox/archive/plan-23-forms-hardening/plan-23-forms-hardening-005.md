envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:34Z

# Candidate lesson: change-ledger append rejects --changed-paths-file; installed surface takes comma-separated --changed-paths

Signal: script failure, notation `plan-marshall:manage-change-ledger:manage-change-ledger` (work log 08:43:09, 09:16:17, 11:05:23 for `--changed-paths-file`; script log 09:43:34 for space-separated paths after `--changed-paths`).

## What happened

Three times, the phase-5 per-deliverable change-ledger append was attempted with `--changed-paths-file`. The installed `append` (plan-marshall 0.1.2184, checked with `append --help` during this capture) declares only `--changed-paths CHANGED_PATHS`, "comma-separated git-sourced changed paths". Once, the paths were passed space-separated, and argparse rejected the extra positional (`unrecognized arguments: git-forms/src/test/.../SchemaValidatorTest.java`). The rows were finally appended with comma-separated `--changed-paths`. Separately, the deliverable-6 entry was first appended with a wrong commit sha (0e97fa5) and then corrected to d84d77c, which leaves a void row in the ledger (decision log 09:29:26).

The orchestrator reports that `--changed-paths-file` is the documented flag. This capture did not trace which document names it. If a workflow doc names it, the doc and the installed script disagree.

## Candidate rule

Find the doc that prescribes `--changed-paths-file` and align it with the installed surface. Either ship the flag in the script, which suits long path lists and keeps them out of the shell, or change the doc to the comma-separated `--changed-paths` form. Until then every deliverable commit pays one rejection.

Scope: plan-marshall:manage-change-ledger and the phase-5-execute step that appends change rows.
