envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:43:52Z

# Workflow docs prescribe `manage-change-ledger append --changed-paths-file`; installed script accepts only `--changed-paths`

Source signal: observed by the orchestrator during the git-core-import finalize run (reported in the
lessons-capture dispatch notes). It is a doc/script drift in the plan-marshall bundle, not a project defect.

## What happened

The workflow prose tells the caller to record changed paths through
`manage-change-ledger append --changed-paths-file <file>`. The installed `manage-change-ledger` script has no
such flag. It accepts only `--changed-paths`. A caller following the doc verbatim gets an argparse rejection and
has to fall back to the inline form.

## Why it matters

The file form exists so that a long path list never goes through the shell argument vector. The inline fallback
works for a short list but defeats that purpose. Any plan with a large footprint (this one changed 26+ paths)
hits the gap again.

## Candidate corrective direction

- Either add `--changed-paths-file` to `manage-change-ledger append` (preferred, it matches the Write-then-pass-file
  pattern used elsewhere), or correct every doc reference to `--changed-paths`.
- Add the `append` canonical-invocation block to the plugin-doctor `manage-invocation-invalid` scan so this drift
  is caught at edit time.
