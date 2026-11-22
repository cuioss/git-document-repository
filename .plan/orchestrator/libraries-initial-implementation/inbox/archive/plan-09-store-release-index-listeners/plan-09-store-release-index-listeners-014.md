envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:50Z

component=git-store
category=bug

# Nested listener delivery waited for another subscription's lock inside a callback, which can deadlock

Source: review-bot finding 58a259 (CodeRabbit, PR #52, ListenerDispatch.java:161), fixed by TASK-12.

## Defect

Listener A on branch X and listener B on branch Y could be delivered at the same time on two threads, each holding its own subscription lock. If A's callback saved to Y and B's callback saved to X, each nested notification blocked waiting for the other subscription lock. Neither callback returned, and both save callers stayed blocked.

## Rule

Callback code must never block on a lock that another callback may hold. Queue cross-subscription delivery without blocking while a subscription lock is held, and keep callbacks serial per subscription. Synchronous delivery after the transport lock is released is the setup to check for lock-order cycles.
