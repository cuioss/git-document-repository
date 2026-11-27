envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:23Z

# Candidate lesson: `manage-change-ledger append --changed-paths-file` undeclared (recurred 5 times)

- Source signal: work-log ERROR lines (not script_failure markers, so outside the counted clusters), notation plan-marshall:manage-change-ledger:manage-change-ledger append, exit 2; 2026-11-27T15:43:55Z, 16:20:59Z, 16:38:13Z, 17:58:15Z, 18:31:01Z
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-5-execute (per-deliverable commit ledger append) / plan-marshall:manage-change-ledger

## Observation

After every per-deliverable commit in phase 5-execute, the ledger append was called with `--changed-paths-file`, which the
script does not declare; each time it was retried with `--changed-paths`. The agent's own log lines call it
"workflow doc drift" and "executor surface lacks it". Five recurrences across four dispatches point to the phase-5-execute
workflow text naming a flag the script never had (or lost).

## Possible rule / follow-up

Reconcile the phase-5-execute per-deliverable commit step with the `manage-change-ledger append` parser: either document
`--changed-paths` or add `--changed-paths-file` (a file form is shell-safer for long path lists). Note also that these failures
were logged as plain `[ERROR]` lines without the `script_failure` marker, so the lessons-capture signal scan did not count them.
