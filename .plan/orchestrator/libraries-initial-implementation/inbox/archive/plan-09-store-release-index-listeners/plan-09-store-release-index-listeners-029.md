envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=landing
created=2026-11-22T11:59:11Z

## What landed

plan-09-store-release-index-listeners shipped as #52 (merged, squash via the platform merge queue, merge commit 4a5eed5f593b4624f7b350358d289168ba1f9ae0).

```landing-facts
schema=landing-facts/1
plan_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
pr=#52
merge_state=merged
cleanup_owed=false
deliverables_total=5
deliverables_done=5
total_tokens=23815683
total_wall_seconds=88108.0
harness=claude
model=claude-sonnet-5-5
effort=low
plugin_version=0.1.2149
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,plan-marshall:plan-retrospective:done,record-metrics:done,emit-landing:done,archive-plan:pending
unresolved_review_threads=0
review_body_findings_total=2
review_body_findings_handled=2
sonar_open_new_issues=0
```

## Residue

- **Deviation, owner-approved 2026-11-21:** the spec said `git-core` stays read-only. A CodeRabbit finding (executable file modes lost in a release) was fixed by adding `TreeEdit.Mode` to `git-core` (`TreeEdit`, `InCoreWriter`, `InCoreWriterTest`, `git-core.adoc` GDR-CORE-4). The owner chose "keep, report it"; the PR description states it. Remaining `git-core` gap, not changed: `git-core` pushes one reference per call, so `main` and the release tag cannot go up atomically (reported as `INCOMPLETE`, repeated release creates the missing tag).
- **New dependency, owner-approved 2026-11-21:** `org.openjdk.jol:jol-core` 0.17, test scope of `git-store` only (GDR-NFR-1 measuring tool); `jol.magicFieldOffset=true` is set in the `git-store` Surefire configuration.
- **Owner decisions of this run:** step refusal reuses `StoreOutcome.REFUSED`; document paths follow the store convention; the release message is the fixed `Release <collection>/<id> v<n>`; the two Sonar issues S1452 and S6539 were fixed, not dismissed (no Sonar dismissal was made).
- **Measurement notes for the four review and Sonar numbers:** `unresolved_review_threads=0`, `review_body_findings_total=2` and `review_body_findings_handled=2` come from the findings store and the respond pass (all 9 inline threads resolved on the provider, both review-body findings answered), and `sonar_open_new_issues=0` is the issues read at head f0208b9 (hotspots not read); they were not re-read at the merge gate itself.
- **Token total is a floor:** `enrich` measured one of two sessions (the post-resume session transcript was not found), and the wall total includes an overnight pause (24h28m wall, 2h13m worked).
- **Merge commit message:** the merge queue offers no per-enqueue message override; the landed squash message follows the repository's queue default, and the PR body carries review-bot text, so the landed message may not be the title-plus-Summary shape.
- **Unpushed local flake notes** (not blockers): `JGitRepositoryOperationsTest.publishLosesRace` (`StackOverflowError`, once) and `ConcurrentWritersTest.tenWritersWithOwnClones` (lost lease on all ten attempts once; fixture cleanup race twice) failed local gates on code this branch did not change and passed on re-run.
- **Inbox:** 28 `candidate-lesson` messages (001-028) were written alongside this landing; the orchestrator pickup must classify them.
