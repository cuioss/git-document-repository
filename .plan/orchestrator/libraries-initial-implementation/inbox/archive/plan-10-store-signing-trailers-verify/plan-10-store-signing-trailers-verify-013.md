envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:40:52Z

component=plan-marshall:phase-5-execute
category=bug
title=Fix Step 10a ledger call: --changed-paths-file is rejected by manage-change-ledger

# Fix Step 10a ledger call: --changed-paths-file is rejected by manage-change-ledger

## Context

In plan-10-store-signing-trailers-verify every phase-5-execute dispatch (at least six: envelopes 1 and 2, fix rounds TASK-10..13, TASK-14, TASK-15, TASK-16/17) called `manage-change-ledger append --changed-paths-file` exactly as Step 10a of phase-5-execute documents. The script rejected the flag with exit 2 each time, and each agent retried with a hand-built comma-separated `--changed-paths` list, which succeeded.

## Root cause

The phase-5-execute workflow text and the `manage-change-ledger append` argparse surface disagree: the documented flag does not exist on the installed executor.

## Proposed action

Either add `--changed-paths-file` to `manage-change-ledger append` or correct Step 10a to the accepted `--changed-paths` form. A `--from-commit SHA` mode that derives the paths from `git diff-tree` would also remove the hand-built list entirely.

## Evidence

- aspect: chat_history_analysis — six subagent hand-backs report "the documented --changed-paths-file flag does not exist ... re-ran with --changed-paths"
- aspect: llm_to_script_opportunities — changed-path list hand-assembled at least 6 times
- aspect: log_analysis — 12 work-log errors, 5 script errors across the plan
