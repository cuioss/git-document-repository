envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=landing
created=2026-11-18T22:59:23Z

## What landed

plan-19-git-worktrees-hardening shipped as #31 (merged, squash via the merge queue as e2aced9).

```landing-facts
schema=landing-facts/1
plan_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
pr=#31
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=12266056
total_wall_seconds=12168
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,plan-marshall:automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
```

## Residue

- Owner decisions taken during the run (operator answers through the question tool): `releaseBranchReservation` added as new public API (GDR-WT-4); `worktreeRemove` returns `WORKTREE_NOT_FOUND` instead of `IDENTITY_MISMATCH` for a foreign repository at the registered path (behaviour change, no shim); finalize was told to merge when green although AGENTS.md otherwise forbids merging without the user's say-so.
- One follow-up fix task (TASK-11) was opened from three CodeRabbit findings and merged in the same PR; CodeRabbit's included-review quota (1 per hour) refused a re-review of the fix commit and was settled by `accept_review_on_record` after a wait of about 31 minutes.
- `effort` is written `unknown`: the stamp's `medium` was entered at plan init without a measured source. `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` are `unknown` because no finalize step records them; the three CodeRabbit threads were replied to and resolved by the unified triage (two thread replies resolved on the provider, one review-body finding answered as a batched comment). `sonar_open_new_issues=0` is the confirmed PR-scoped new-code issue count at the merged head; security hotspots were not read.
- The `steps` value lists the steps with a recorded terminal outcome when this message was written; `emit-landing` (this step) and `archive-plan` follow it.
- The accumulator for finalize usage was corrected once by hand after an estimate was booked before a dispatch's real usage arrived (tokens and tool calls exact, duration off by 3 ms, sample count one higher than real).
