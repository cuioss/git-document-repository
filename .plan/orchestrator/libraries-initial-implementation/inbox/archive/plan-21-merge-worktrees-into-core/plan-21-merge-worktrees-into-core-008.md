envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=landing
created=2026-11-20T17:52:02Z

## What landed

plan-21-merge-worktrees-into-core shipped as #46 (merged).

```landing-facts
schema=landing-facts/1
plan_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
pr=#46
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=4
total_tokens=14188567
total_wall_seconds=10740.0
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done,emit-landing:done
unresolved_review_threads=unknown
review_body_findings_total=unknown
review_body_findings_handled=unknown
sonar_open_new_issues=0
```

## Residue

- Merge: PR #46 landed on main as 9e3cfb0 through the GitHub merge queue (squash), corroborated by the queue-landing gate. The queue squash message was not set by this run; the commit message on main may carry the PR body, which includes the CodeRabbit release-notes text.
- Owner decisions taken during outline: requirements GDR-WT-1..4 renumbered to GDR-CORE-12..15; log ids INFO 2-4 / WARN 102-104 with the worktrees refusal kept as WORKTREE_CAPABILITY_REFUSED=102; ADR-0004 rewritten in place (no new ADR). The outline step re-froze the success criteria on its own authority; the operator approved that after the fact.
- Sonar java:S4449 in the moved JGitWorktreeOperations.existingBranchStart was fixed at operator selection (commit 6b45edb, one loop-back round); Sonar then confirmed 0 new-code issues. `sonar_open_new_issues=0` is that confirmed read.
- `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` are written `unknown`: no lifecycle step records them as facts (the landing is INCOMPLETE at those keys, as the payload spec states). For reference, the triage handled all four findings that existed (three CodeRabbit, one Sourcery), none left pending at merge.
- `effort` is `unknown`: the plan's stamp carries no effort value.
- The commit trailer of 6b45edb names "Claude Opus 5.5" although the session ran on Sonnet 5.5; it is merged and was not rewritten.
- The inbox holds seven candidate-lesson messages for this plan (001-007) written by lessons-capture and the retrospective.
