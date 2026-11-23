envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:34:35Z

# Candidate lesson: release recovery accepted a save commit that carried a release subject

Source: review-bot findings 1c34d9 (PR #58, DocumentRelease.java:392, Major) and 0b167f (ExplicitSave.java:283, Major), both resolution fixed (TASK-12, TASK-17).

Component: git-store (DocumentRelease, ExplicitSave)

## What happened

Release recovery recognised an already-written release commit on `main` by comparing the first paragraph of the message with the release subject. A save commit could carry the same subject (with a body), so recovery could tag an ordinary save as the release. The first fix (TASK-12) made the save refuse the release subject, but compared it only against the save's own key; a save to `main` with another key could still carry the release subject of a different entry (round 2 finding, TASK-17). The final fix reserves the release-subject format on every save to `main`.

## Candidate rule

When a recovery path recognises "the commit an earlier attempt wrote" by a property of the commit, that property must be one the ordinary write path cannot produce. Check the discriminator against every writer of the same branch, not only the writer with the matching key. Signatures and identity trailers are not a discriminator, because every save writes them too.
