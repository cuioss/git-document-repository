envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:25Z

# Candidate lesson: script argument rejections during the run

Source: script-failure signal, one counted cluster: plan-marshall:manage-findings:manage-findings.

## Counted record

- 2026-11-19T21:22:13Z, phase 3-outline re-entry: `manage-findings qgate list` rejected with exit_code=2, failure_kind=argparse_rejection, missing required `--phase`. This is the known recurrence signature 5 (missing required --phase on phase-scoped finding verbs).

## Related rejections logged in the work log (not counted by the scan, same class)

- 2026-11-19T22:01:51Z and 2026-11-20T06:39:14Z, phase 5-execute: `manage-change-ledger append --changed-paths-file` rejected by the worktree executor (exit 2, unknown flag); retried with `--changed-paths`. It happened twice in one run, so the documented flag and the worktree executor's declared flags disagree, or the caller relies on a flag that only the newer bundle declares.
- 2026-11-19T22:18:07Z, phase 5-execute: `architecture search --content ... --module git-worktrees` rejected (unknown flag --module); retried without it.

## Candidate rule

Quote flags from the live --help of the executor in use (the worktree may resolve a different bundle version than the main checkout: 0.1.2167 vs 0.1.2171 were both present this run). The change-ledger `--changed-paths-file` mismatch looks like a version-skew or doc drift worth checking in plan-marshall.
