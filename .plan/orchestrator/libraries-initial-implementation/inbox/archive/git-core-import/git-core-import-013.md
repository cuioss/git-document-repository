envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=landing
created=2026-11-18T13:51:42Z

## What landed

git-core-import shipped as #25 (merged).

```landing-facts
schema=landing-facts/1
plan_id=git-core-import
epic=libraries-initial-implementation
pr=#25
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=20926368
total_wall_seconds=26932.0
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

- Merged through the platform merge queue as 2c728bc (squash). All review threads were answered; the review-thread and review-body counts are not recorded by any finalize step, hence `unknown`.
- Operator decisions that shaped the run: GitOutcome naming (option A, GDR-GEN-3 names, worktree outcomes stay in git-core); two CodeRabbit hardening findings (system git config loading, HTTP redirect targets) were fixed as tasks 19 and 20; one CodeRabbit finding (7e5661, http.extraHeader/cookieFile from user/system config) was declined with a documented reason; one extra triage round was granted to clear the last Sonar S5778 instance.
- Known residual gaps recorded as inbox candidate lessons 012: JGit can still start `git` once per JVM to read the system config unless an application installs the opt-in `RefusingFS.systemReader`; the redirect tests run at transport level, not end-to-end; refusal of a hook on the remote side is untested.
- Effort was not stamped at plan start (`effort=unknown`).
