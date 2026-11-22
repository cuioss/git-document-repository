envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:51Z

component=git-store
category=bug

# Subscription close() could return while a callback was about to start, which broke the close contract

Source: review-bot finding 0ab574 (CodeRabbit, PR #52, ListenerDispatch.java:245), fixed by TASK-12.

## Defect

Delivery checked `closed` and then invoked the callback. A `close()` that ran between the check and the invocation returned, and the callback still ran afterwards. That broke the public contract that no callback runs after `close()` returns.

## Rule

A check-then-act on a closed flag must happen under the same lock that `close()` takes. Make `close()` acquire the subscription's reentrant lock before it sets `closed`, so an external close waits for an active delivery and a callback can still close its own subscription.
