envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=landing
created=2026-11-19T14:28:10Z

## What landed

git-core-incore-write-cas shipped as #35 (merged, squash through the merge queue as c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c): in-core writing (GDR-CORE-4), compare-and-swap and compare-and-delete reference updates (GDR-CORE-5) and the hardening items (b), (c), (e), (f), (g), (h) of the PLAN-02 repository operations; (a), (d) and (i) are recorded as accepted exceptions in doc/specification/git-core.adoc.

```landing-facts
schema=landing-facts/1
plan_id=git-core-incore-write-cas
epic=libraries-initial-implementation
pr=#35
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=13633697
total_wall_seconds=93152.0
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

- Merge was held until the owner said to merge: the manifest carried final_merge_without_asking=true, which contradicts the AGENTS.md rule "do not merge unless the user says so". The owner approved in chat; the manifest value was not changed.
- Review counts (unresolved_review_threads, review_body_findings_total, review_body_findings_handled) and effort are `unknown`: no phase-6-finalize step records them and the effort stamp is absent from the plan metadata. For orientation only: the 5 CodeRabbit inline threads of PR #35 were replied to and resolved at commit 2c2c8e0; CodeRabbit refused further review on quota afterwards (required bot counted as participated on its earlier review of the PR); Sonar showed 0 new-code issues at the fix commit.
- Token totals: the 5-execute phase-boundary was stamped with --total-tokens 743 instead of 742996 and cannot be corrected (end-phase adds, no subtract verb). The retrospective found the typed phase figures about 388K short of the boundary rows (candidate lesson 014); the landing total is the enriched report total.
- create-pr was executed inline instead of through a dispatch. Tracking: sonar_open_new_issues is the sonar-roundtrip step's own recorded fact (0, confirmed), measured at the fix commit and not re-read after the merge.
- Candidate lessons: lessons-capture 001-012 and the retrospective 013-016 are in the epic inbox.
