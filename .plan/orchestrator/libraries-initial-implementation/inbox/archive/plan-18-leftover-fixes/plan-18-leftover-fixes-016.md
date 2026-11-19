envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=landing
created=2026-11-18T13:29:22Z

## What landed

plan-18-leftover-fixes shipped as #28 (merged via the merge queue as 7418f76).

```landing-facts
schema=landing-facts/1
plan_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
pr=#28
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=15844675
total_wall_seconds=28169.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
```

## Residue

- The `create-pr` step recorded `pr_number=26`. PR #26 was later closed and re-opened as #27 and then #28 by the automatic-review rate-limit recovery (CodeRabbit free review quota), with no operator ask before the first two. The PR that merged is #28, so `pr` above names #28, not the recorded #26.
- CodeRabbit never reviewed the final commit (e5d07ec, a pure helper rename for Sonar S6213); its quota refusal for that head was observed before the merge, and the merge was made on the operator's explicit instruction ("Merge PR #28 now"). Sourcery (optional) was refused on its hard quota throughout.
- `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` are `unknown`: no phase-6 step records them. The 12 CodeRabbit threads on #28 were all answered and resolved on the provider (11 fixed, 1 taken into account at the operator's decision).
- `effort` is `unknown`: the stamp carries no effort value.
- `sonar_open_new_issues=0` is the revision-checked read for head e5d07ec (gate OK, new coverage 100%); hotspots 0.
- Deviations worth the epic's attention: the finalize triage's inline edits and the fix commits were refused by the commit gate until a sanction existed (one commit went under `--step sonar-roundtrip`), and the pre-push gate was recorded after the whole-tree verify only for the last rename commit.
