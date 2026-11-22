envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:53:00Z

component=git-store
category=bug

# A fallback debug log in the index named the document id, which GDR-STORE-9 forbids

Source: review-bot finding 88df2e (CodeRabbit, PR #52, StoreIndex.java:331), fixed by TASK-13.

## Defect

When the previously indexed commit could not be read, a debug message logged `source`. That can be `refs/heads/<collection>/<id>`, so the message recorded the document id. GDR-STORE-9 prohibits ids in index messages.

## Rule

A reference name in this store contains the document id. Treat any reference name or path as data that may identify a person, and log only the repository alias and the outcome. This applies to debug and fallback messages as well, not only to INFO and above.
