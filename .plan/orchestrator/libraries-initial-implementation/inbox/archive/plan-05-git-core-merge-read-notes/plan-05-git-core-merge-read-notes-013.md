envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=landing
created=2026-11-19T19:35:56Z

## What landed

plan-05-git-core-merge-read-notes shipped as #37 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
pr=#37
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=16590381
total_wall_seconds=23506
harness=claude
model=claude-sonnet-5-5
effort=medium
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done,emit-landing:done,archive-plan:unknown
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=unknown
step.finalize-step-sync-baseline.action=noop
step.finalize-step-sync-baseline.upstream_commit_count=0
step.finalize-step-sync-baseline.work_performed=true
step.create-pr.pr_number=37
step.sonar-roundtrip.count_status=confirmed
step.sonar-roundtrip.new_code_issue_count=0
step.sonar-roundtrip.issues_fetched=0
step.sonar-roundtrip.work_performed=true
step.branch-cleanup.merge_mechanism=merge_queue
step.branch-cleanup.merge_state=merged
step.branch-cleanup.cleanup_owed=false
step.branch-cleanup.work_performed=true
step.record-metrics.total_tokens=16590381
step.record-metrics.total_wall_seconds=23506
step.record-metrics.any_phase_missing_end_time=false
step.record-metrics.harness=claude
```

## Residue

- Merge commit 14269fef44b97f690f104f420c88ef69db6d5c3d on main (PR #37, merged through the merge queue).
- `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` are `unknown`: no lifecycle finalize step records them as facts yet (named producer gap). automatic-review reported "0 comment(s) found - 1 reviewed, 1 refused-structural (unified triage pending)" as prose only.
- `sonar_open_new_issues` is `unknown`: sonar-roundtrip recorded `new_code_issue_count=0` (confirmed), which counts issues without security hotspots, so it is not this key (named producer gap).
- `archive-plan:unknown`: archive-plan runs after this landing is written, so its outcome cannot be settled here.
- `model` is the start-time self-reported stamp (`model_source: self_reported`); the landing-time refresh-runtime-stamp could not measure it (`reason: model_unknown`). `effort` is also self-reported.
- Phase 6 needed several firings of ci-verify, automatic-review and sonar-roundtrip (3 each) and three fix tasks (12-14) from review rounds on PR #37; the wait-region-unified-triage loop-back budget shows spent=2 against granted=0.
