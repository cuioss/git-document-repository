envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:42Z

component=git-store
category=bug

# An incomplete release was recognized by its commit message alone, so an ordinary save could pass as one

Source: review-bot finding ad04ad (CodeRabbit, PR #52, DocumentRelease.java:353), fixed by TASK-10.

## Defect

The recovery path for an incomplete release took a `MAIN`-collection commit whose message was `Release <collection>/<id> v<n>` to be the missing release. An ordinary save writes the caller's message unchanged and can carry that same text. `tagAndPush` then tagged an unrelated commit and returned `OK` without merging the document's working branch.

## Rule

A recovery guard must identify its target by data that only the guarded operation can produce, such as dedicated release metadata or a trailer, plus a check of the document and the version. Free text the caller controls is not enough. Check the guard's predicate against a crafted input that imitates the scenario.
