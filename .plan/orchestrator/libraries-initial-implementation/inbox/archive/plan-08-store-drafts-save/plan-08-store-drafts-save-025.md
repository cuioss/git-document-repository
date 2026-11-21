envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=landing
created=2026-11-21T11:00:32Z

## What landed

plan-08-store-drafts-save shipped as #48 (merged). Draft write path of git-store: drafts, explicit save with lease, conflicts, change detection, expiry; two review-fix rounds after the first green build.

```landing-facts
schema=landing-facts/1
plan_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
pr=#48
merge_state=merged
cleanup_owed=false
deliverables_total=7
deliverables_done=7
total_tokens=22480486
total_wall_seconds=109488
harness=claude
model=claude-sonnet-5-5
effort=unknown
plugin_version=0.1.2149
executor_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done,emit-landing:done
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
```

## Residue

- Scope deviation: review-fix tasks edited git-core although the outline declared it read-only (FetchInput.prune; JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS made public; git-core.adoc). Recorded for the operator; not asked for beforehand.
- Operator decisions in this run: save.max-attempts upper bound 100; commit.email setting (default false) for author and committer, committer address from the technical user's configuration, author address supplied by the caller; garbage collection after expiry immediately under an exclusive lock; shared guard per clone path; kept newer draft tracked through refs/applied/; an address in a save message is out of scope (reply only, review finding 9ba59d rejected).
- The unified triage ran twice (loop-back rounds 1 and 2 of 3): 18 valid review findings and 33 Sonar findings were fixed in code after a green local build; no suppression was used.
- Token total is a floor for 1-init (not measured); effort of the run was not stamped (written unknown).
- Commit trailers on the branch mix Claude Sonnet 5.5 and Claude Opus 5.5 lines (agent models differed).
