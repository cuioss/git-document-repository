envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:36Z

component=plan-marshall:manage-change-ledger
category=bug
signal=script-failure
shape=actionable

# manage-change-ledger append rejects --changed-paths-file

## What happened

In plan-17-timestamp-rfc3161, `manage-change-ledger append --changed-paths-file PATH` was rejected by argparse. The installed tool only accepts a comma-separated `--changed-paths`. The call that a workflow or the caller expected to work failed, and had to be retried with the inline list.

## Corrective action

- Either add `--changed-paths-file` to `append` (preferred: a long path list is safer in a file than inline, as the other manage-* scripts already do) or remove every reference to it from the workflow docs that prescribe it.
- Check the canonical-invocations block of `manage-change-ledger` against the live `--help` output, so the documented and installed surfaces cannot drift apart.

## Impact

A script failure cluster and a retry. A long path list inline is also fragile.
