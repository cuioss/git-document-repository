envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:23Z

# Candidate lesson: documentation prescribes `--changed-paths-file` on change-ledger append, the script does not declare it

**Source:** orchestrator observation, confirmed by three [ERROR] lines in the work log (08:50:59Z, 09:15:07Z,
11:20:47Z): `manage-change-ledger append failed: exit_code=2, args=--changed-paths-file (unknown flag; use
--changed-paths)`
**Notation:** plan-marshall:manage-change-ledger:manage-change-ledger

## Observation

The phase-5-execute Step 10a text and the execution.md recovery text tell the agent to call
`manage-change-ledger append --changed-paths-file ...`. The installed script declares only `--changed-paths`.
Every per-deliverable commit in this plan hit the rejection and had to be retried with `--changed-paths`. The
leaf followed the documentation, so this is a doc/script drift, not an agent mistake.

## Candidate rule

Either add `--changed-paths-file` to `append` (a file input is the shell-safe form for long path lists), or
correct phase-5-execute Step 10a and the execution.md recovery text to `--changed-paths`. A doc-vs-argparse
check would catch this class.

Note: these lines were not counted as script_failure clusters by signal-scan (they carry the phase-level
[ERROR] form, not the execute-script script_failure marker).
