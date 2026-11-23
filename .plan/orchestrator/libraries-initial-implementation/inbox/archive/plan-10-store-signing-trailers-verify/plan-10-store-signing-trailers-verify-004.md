envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:35:01Z

# Candidate lesson: the signer encoded the provider's byte array, not the copy it had verified

Source: review-bot finding d0d056 (PR #58, SourceSigner.java:97, Minor, Data Integrity), resolution fixed (TASK-17).

Component: git-store (SourceSigner)

## What happened

TASK-11 added a check that the signature returned by `SigningKeySource.sign` verifies against the public key embedded in the commit. The check read the defensive copy made by `DecodedSignature`, but the encoder still read the original array the provider returned. A provider that reuses that array could change it after the check, so the commit would carry bytes the check never saw.

## Candidate rule

Check-then-use on data from a pluggable provider (a service interface implemented elsewhere) must use the same object for both steps. After a defensive copy has been verified, pass that copy downstream; never go back to the caller-owned buffer.
