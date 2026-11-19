envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:28:05Z

component=plan-marshall:phase-5-execute
category=bug
title=Align phase-5 change-ledger append docs with the script's --changed-paths flag

# Align phase-5 change-ledger append docs with the script's --changed-paths flag

## Context

Three separate phase-5-execute dispatches of plan-18-leftover-fixes (envelope 1, envelope 2, and the TASK-10 and TASK-11/12 fix passes) reported the same deviation: the phase-5 workflow (Step 10a) tells the leaf to call `manage-change-ledger append --changed-paths-file`, but the installed script rejects that flag and only accepts `--changed-paths` with a comma-separated list. Each leaf hit the argparse rejection, then retried with the comma-joined form.

## Root cause

The phase-5 workflow documents a flag the `manage-change-ledger append` parser does not declare (doc/script drift).

## Proposed action

Either add `--changed-paths-file PATH` to `manage-change-ledger append` (preferred: shell-safe for long path lists and already what the workflow prescribes), or change the phase-5 Step 10a invocation to the existing `--changed-paths` form. Add a canonical-invocation check so the two cannot drift again.

## Evidence

- aspect: chat_history_analysis - three execute hand-backs name the `--changed-paths-file` rejection and the comma-separated workaround
- aspect: llm_to_script_opportunities - 4 hand-joined path lists across the plan
- Not covered by the 12 lessons-capture messages (listed there as topic 3, not sent)
