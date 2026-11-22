envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:53:01Z

component=git-store
category=bug

# A failed index refresh was not retried after a fetch that moved no references

Source: review-bot finding 8d5f25 (CodeRabbit, PR #52, StoreWriter.java:379), fixed by TASK-13.

## Defect

`StoreEvents.emit` returned early when the update list was empty. If an index refresh failed after the clone moved to B, a later fetch that moved no references never reached the index observer. The index stayed at A while the clone was at B, until some later operation happened to move a reference.

## Rule

Do not gate recovery of a failed derived state on the next change event. A periodic trigger such as a fetch must retry a refresh whose last attempt failed, even when nothing moved. Listener delivery can stay limited to actual moves.
