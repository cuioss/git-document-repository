envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=landing
created=2026-11-24T07:11:27Z

## What landed

plan-20-leftover-fixes-2 shipped as #69 (merged), after PR #67 and #68 were closed and replaced by the CodeRabbit quota recovery.

```landing-facts
schema=landing-facts/1
plan_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
pr=#69
merge_state=merged
cleanup_owed=false
deliverables_total=6
deliverables_done=6
total_tokens=25976485
total_wall_seconds=53696.0
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

- Merge commit f7eda4e on main, through the platform merge queue, after a rebase onto b30ec14 that the operator authorized.
- The rebase advanced HEAD past four head-dependent verdicts (simplify, ci-verify, automatic-review, sonar-roundtrip), which were re-run at 5baca51 before the merge.
- 14 CodeRabbit comments, 5 Sonar issues (one BUG failing the reliability condition) and one further CodeRabbit major item were triaged: 4 fix tasks and 4 inline edits were applied, one item was accepted as a documented trade-off, one was declined with evidence (the reference-name guard refusing `HEAD` is the decided contract). The three review-count keys above are `unknown`: no finalize step records them yet, and the effort stamp was not passed at init.
- Not run against the base: the fail-first runs for deliverable 4 (stated in the PR body).
- `pre_merge_gate record-merge` refused the truthful combination "rebased + merge_queue" (`action_on_queue_route`); the step was recorded with `manage-status mark-step-done` and the same fact keys instead.
- Candidate lessons: 20 inbox messages (plan-20-leftover-fixes-2-001 to -020).
