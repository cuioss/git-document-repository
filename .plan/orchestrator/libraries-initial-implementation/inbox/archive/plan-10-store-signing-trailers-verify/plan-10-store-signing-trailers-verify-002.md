envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:34:44Z

# Candidate lesson: signature verification reported success over a truncated (shallow) history

Source: review-bot findings 1d371c (PR #58, SignatureVerification.java:139, Major, Security) and 224e3d (SignatureVerification.java:148, Minor, Security), both resolution fixed (TASK-11, TASK-18).

Component: git-store (SignatureVerification, VerifySignatures)

## What happened

The verifier walked the commits reachable from a reference and listed every commit without a valid signature. Behind a shallow boundary the walk stopped, so an unsigned ancestor was never visited and `VerifySignatures` could exit VERIFIED. TASK-11 returned a non-success outcome when a shallow boundary is reachable. The next review round found that the shallow set was read before the RevWalk started, while the walk reads it again on parse; a boundary added in between was missed. TASK-18 reads the boundary again after the walk and ends INCOMPLETE on any difference, with a regression test.

## Candidate rule

A verifier that reports "every item is valid" must prove it saw every item. An empty list of failures is not success when the walk can be cut short (shallow boundary, missing objects, depth limit). Check the completeness condition against the same state the walk actually used, not a snapshot taken at another time.
