envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:41Z

component=plan-marshall:execute-task
category=bug

# A fix task edited git-core although the plan spec declared git-core read-only; no guard stopped it

Source: orchestrator observation in this run (TASK-010 wrote git-core/src/main/java/de/cuioss/git/document/core/InCoreWriter.java, TreeEdit.java, InCoreWriterTest.java and doc/specification/git-core.adoc).

## What happened

TASK-010 fixed review finding b186a7 (file modes in the release tree). It did this by extending `TreeEdit` and `InCoreWriter` in `git-core`, but the plan spec said `git-core` stays read-only. No step refused or flagged the edit: not the fix-task creation, not execute-task, not the per-deliverable commit. The orchestrator noticed it from the leaf report and asked the owner after the fact.

## Rule

A fix task created in a finalize loop-back inherits the plan's declared footprint and its read-only modules. Before committing, execute-task should compare the changed paths with the read-only modules in the plan spec or outline. On a hit it should stop and return a prompt-required envelope, so the owner decides before the commit, not after it. When a review finding can only be fixed inside a read-only module, the triage should return that decision to the operator rather than open a fix task that crosses the boundary.
