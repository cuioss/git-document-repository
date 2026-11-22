envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:48Z

component=git-store
category=bug

# A new branch's history boundary was read at dispatch time, so a concurrent release could hide the saved commit from listeners

Source: review-bot finding 79143c (CodeRabbit, PR #52, ExplicitSave.java:395), fixed by TASK-12.

## Defect

When a save created a branch, the reference update recorded `oldId=null` and dropped the known base commit. `ListenerDispatch.markBoundary` read the `main` tip later, at dispatch. If a concurrent release had merged the saved commit into `main` by then, the commit was excluded as inherited history, and a listener that subscribed before the branch existed never received it.

## Rule

Capture any boundary that event delivery depends on while the lock that made the change is still held, and carry it in the event. Do not re-derive it from mutable repository state after the lock is released.
