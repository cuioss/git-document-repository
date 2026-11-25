envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:39Z

Candidate lesson (retrospective: documented --changed-paths-file flag rejected by manage-change-ledger append)

component=plan-marshall:manage-change-ledger
category=bug
title=Accept the documented --changed-paths-file flag on append

## Context

In TASK-4, TASK-6 and the finalize commits, leaves followed the documented `manage-change-ledger append --changed-paths-file` and the installed executor rejected the flag; each leaf then rebuilt a comma-separated `--changed-paths` list by hand (49 paths in one case). The lessons-capture step noted the two failures (log lines 28d772 and d6bbf5) but did not route them because the signal scan does not count them.

## Root cause

The documentation and the installed argparse surface of `append` disagree on the path-list flag.

## Proposed action

Either add `--changed-paths-file` to `append` (shell-safe for long lists) or correct the documentation to the accepted flag; a `--from-commit SHA` form would remove the hand-built list entirely.

## Evidence

- aspect: chat_history_analysis - TASK-4 and TASK-6 hand-backs: "The documented --changed-paths-file flag does not exist on the installed manage-change-ledger append"
- aspect: llm_to_script_opportunities - hand-built path list repeated 3 times
- lessons-capture hand-back: "manage-change-ledger append failed twice ... log lines 28d772 and d6bbf5 ... no message sent"
