envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=landing
created=2026-11-27T20:02:54Z

## What landed

plan-14-forms-deadlines-links-numbers shipped as #81 (merged, squash through the merge queue, merge commit 0187cf3).

```landing-facts
schema=landing-facts/1
plan_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
pr=#81
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=24572720
total_wall_seconds=32446.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
```

## Residue

- Success criteria of deliverables 5 and 7 require each new behaviour test to fail at the base commit (bdbb5e4 for 5, e38e079 or c22700a for 7) and pass at the head. No run executed that check; the executors argued it from the code and the tasks were closed anyway. The fix tests of review rounds (tasks 15 to 18) were likewise written after the source change, not observed red first.
- Deliverable 7(c) was narrowed at the outline: the unbounded read it cited no longer exists since c22700a (bounded at 16 MiB). Only a test and wording remain; a MALFORMED diagnostic would need a git-store change and was not made.
- Scope lift at the operator's decision: the plan's "no git-store change" constraint was lifted for one minimal public accessor, StoreIndex.sourceEntries(source, collection), with its test and its description in doc/specification/git-store.adoc (review finding 7f13a5). Three changed files lie outside the outline: doc/specification/general.adoc (a table row a test requires), StoreIndexTest.java and git-store.adoc.
- Sonar: three findings (LinkTargets S4968, TransitionStep S107, DataNode S1643) were kept as in-code suppressions at the operator's selection; a fourth, S1135 on the generated TODO in UnchangedSave, was fixed with the repository's cui-rewrite:disable marker at the operator's instruction. Sonar quality gate OK and 0 new issues at the merged head 5b439be.
- ADR-0005 of cuioss/Audit-Notary says step-up is MFA by default; deliverable 6 keeps that default but adds the switch and single-method values. The ADR itself was not edited and is flagged for the consumer.
- The squash commit on main carries CodeRabbit's "Summary by CodeRabbit" text in its body, as the earlier landings 76, 78 and 79 do; the merge queue cannot set a per-merge message.
- Cost: the run spent 24.6M tokens in total (population mixed) against a 2.5M-token budget for this plan size; finalize alone used 4.1M tokens in 5 review rounds, 3 triage loop-backs and 6 simplify runs. A first branch-cleanup hand-off (235K tokens) was refused with merge_candidate_not_gated because the simplify record was one commit behind the head; that was knowable before the hand-off.
- The four review-thread measurements (unresolved_review_threads, review_body_findings_total, review_body_findings_handled) are written unknown: no finalize step records them as facts. For orientation only: all 56 stored findings were resolved and every review thread was answered before the merge.
- Architecture data of module git-forms declares no skills_by_profile for implementation and module_testing; this recurs for every plan touching the module until the data is filled in.
