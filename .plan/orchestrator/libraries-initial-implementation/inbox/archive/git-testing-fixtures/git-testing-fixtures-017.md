envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=landing
created=2026-11-18T06:59:28Z

## What landed

git-testing-fixtures shipped as #23 (merged).

```landing-facts
schema=landing-facts/1
plan_id=git-testing-fixtures
epic=libraries-initial-implementation
pr=#23
merge_state=merged
cleanup_owed=false
deliverables_total=3
deliverables_done=3
total_tokens=20237347
total_wall_seconds=82387.0
harness=claude
model=claude-sonnet-5-5
effort=low
plugin_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=unknown
```

## Residue

- `steps` lists the steps recorded when this message was written; `emit-landing` (this step) and `archive-plan` follow it and are not in the list.
- The four review and Sonar counts are `unknown` because no finalize step records them and the post-merge Sonar read is undecidable (`head_revision_unavailable`, PR merged). Observed before the merge, not measured by this step: `sonar-roundtrip` recorded 0 new-code issues (count_status confirmed) at head 0822d2f, the Sonar quality gate passed, all 15 CI checks were green, and the 6 inline CodeRabbit threads were each answered and resolved.
- Merged through the platform merge queue as squash commit 6cc64f0c6512d139846af9edca670da3a1032032 (no rebase or force-push by the plan).
- Sonar java:S5443 on `TemporaryDirectories` was fixed in code (owner-only temporary directory, TASK-8) on the operator's instruction; the earlier "false positive" dismissal was never accepted by Sonar and was not used.
- The `wait-region-unified-triage` loop-back budget was spent four times (one operator-approved extra round). The run used 20.2M tokens in total (the retrospective reports the earlier phase-1 figure as unmeasured), well over the multi-module budget; the retrospective's candidate lessons 014 and 016 cover the cause.
- `git-testing/pom.xml` sets Surefire `useModulePath=false` (the module's tests run on the class path, with a comment in the pom).
- Subagent commits on the branch carry a `Co-Authored-By: Claude Opus 5.5` trailer while the session model is Sonnet 5.5.
