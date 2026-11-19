envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:28:27Z

# Finalize commit guard refuses review-driven inline edits without an open fix task

**Source signal:** script failure cluster `plan-marshall:workflow-integration-git:git-workflow` (2 refusals)

**Component:** plan-marshall:workflow-integration-git (commit guard), plan-marshall:phase-6-finalize (unified triage)

## What happened

In the second review round on PR #37 the unified triage, running in 6-finalize, applied the coderabbit and Sonar fixes inline in the worktree (ReferenceUpdater.java, ReferenceUpdaterTest.java, RedirectEndToEndTest.java, RepositoryReaderTest.java, doc/specification/git-core.adoc) and then tried to commit them. `git-workflow` refused twice with `reason: no_sanction`:

- 2026-11-19T19:05:39Z: `step: null`
- 2026-11-19T19:05:49Z: `step: "plan-marshall:automatic-review"`

The message was: "Source-tree commit refused in 6-finalize: no step id from the composed phase-6 manifest, no open loop-back fix task and no review-rubric section-6 inline-correction record."

`plan-marshall:automatic-review` was not accepted as the `--step` value, even though `automatic-review` is a composed phase-6 step (the manifest lists it without the bundle prefix). The triage then opened TASK-014 only so it could commit the edits that were already applied. That forced a full loop-back to 5-execute, then simplify, the pre-push gate, ci-verify, sonar and automatic-review all ran again.

In round 1 (iteration 1) the inline edits did commit (ec18817), alongside the fix tasks TASK-12 and TASK-13. So the guard accepts inline edits only when a fix task happens to be open at the same moment.

## Candidate rule

When the triage applies review fixes inline in 6-finalize, either record the sanction first (the section-6 inline-correction record, or the unprefixed manifest step id `automatic-review`), or open the fix task first and apply the edits inside it. Do not apply the edits and then hunt for a sanction. Also check whether `--step` should accept the bundle-prefixed step notation.
