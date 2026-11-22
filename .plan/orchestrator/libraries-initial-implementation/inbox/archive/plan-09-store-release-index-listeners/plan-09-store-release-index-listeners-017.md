envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:59Z

component=git-store
category=bug

# Index snapshots called IndexContributor without the refresh lock, which broke the sequential-callback contract

Source: review-bot finding 145274 (CodeRabbit, PR #52, StoreIndex.java:229), fixed by TASK-13.

## Defect

Refresh took `lock` before it called the contributors. The snapshot path did not. A snapshot during a refresh could therefore call the same `IndexContributor` concurrently, which violated the documented contract that contributor callbacks run one after another and could corrupt a stateful contributor.

## Rule

When a callback interface documents sequential calls, every path that calls it must go through the same serialization point. Count the call sites of the interface, not only the main path.
