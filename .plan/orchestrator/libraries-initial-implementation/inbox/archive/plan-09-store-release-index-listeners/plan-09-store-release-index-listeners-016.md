envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:52Z

component=git-store
category=bug

# A null-cursor boundary dropped commits when two events for one branch reached dispatch out of order

Source: review-bot finding 354869 (CodeRabbit, PR #52, StoreEvents.java:155), fixed by TASK-12.

## Defect

A subscription to a branch that did not exist yet had no cursor, so `markBoundary` used each move's old id as the boundary. Events are emitted after the lock is released, so two operations on one branch can reach dispatch out of order. Example: one save creates `b` at X, a second moves it from X to Y. If X->Y is dispatched first, it delivers Y and sets the cursor to Y. The null->X move then walks from X with Y as the boundary, delivers nothing, and X is lost.

## Rule

The guarantee is "every reachable commit exactly once, oldest first, in any arrival order". Derive the boundary from Git reachability against what has already been delivered, or serialize delivery per branch. Do not rely on the old id of the event being processed. Test the guarantee by dispatching two events of one branch in reverse order.
