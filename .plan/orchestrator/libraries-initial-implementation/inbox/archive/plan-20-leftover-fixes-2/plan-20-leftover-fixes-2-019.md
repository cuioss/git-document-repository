envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:09:34Z

# Candidate lesson: 65 Maven builds ran but the change-ledger holds no build row, so build time is unmeasured

Source: plan-retrospective (finalize), log-analysis aspect (build_time block and build_count_reconciliation).

The facts:
- The script log records 65 `plan-marshall:build-maven:maven` calls, which together used about 4,540 s (29.6% of all script time).
- The change-ledger is present and readable. It has 16 rows, and none of them is a build row with a duration (summed_rows 0). So `total_build_seconds` is `unavailable`, and the build-count reconciliation could not give a verdict.
- The execute freshness gate still matched a ledger row ("ledger row 127 ... written by your verify -Ppre-commit run"). That row lives in the worktree's ledger. The retrospective, run on the main checkout after the worktree was removed, sees no build rows.

Observation: either the build rows were written to a ledger that did not survive the worktree's removal, or the worktree's older script runner (see message 012, where `--changed-paths-file` was not accepted) wrote them in a form the reader does not count. In both cases the plan's build cost cannot be read after the merge.

Candidate rule (for the orchestrator to classify): find out where build rows land during a worktree plan and whether integrate-into-main carries them back. If they are dropped, carry them back. If the runner is too old, flag the version skew when the plan starts.

Related: message 012 (change-ledger append surface mismatch in the same worktree).

Context: plan plan-20-leftover-fixes-2.
