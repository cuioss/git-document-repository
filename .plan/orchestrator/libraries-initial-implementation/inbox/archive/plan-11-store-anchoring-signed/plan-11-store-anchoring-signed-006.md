envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:51Z

# Candidate lesson: review bot caught many concurrency and integrity defects in the anchoring code (PR #66)

Source: the automatic-review signal. CodeRabbit filed review findings on PR #66. Sixteen inline findings were remediated in this run through fix tasks TASK-8 to TASK-13; TASK-14 to TASK-16 were added in a second loop-back. Three triage loop-back iterations were needed in total.

The fixed findings fall into a few recurring defect classes in `git-store` (AnchorEngine, AnchorNotes, Checkpoints, StoreClone, StoreReferences, AnchorVerification):

- Ignored `RefUpdate.Result`. A retained proof ref or checkpoint ref update that returned `LOCK_FAILURE` was treated as success before a push (AnchorNotes:240, Checkpoints:333 and Checkpoints:344).
- Lease and proof disagreement. A proof was obtained against one notes tip and published with a lease on a later tip, so the chain could fork. The fix binds each proof to the tip it extends (AnchorNotes:274 and AnchorEngine:165).
- Annotated tags not peeled to their commit before being used as a parent or compared (Checkpoints:122).
- Unreadable or missing targets read as clean in verification (AnchorVerification:343).
- Fetch updates not dispatched to `StoreEvents`, leaving the index stale (AnchorEngine:197).
- Schedule cancellation identified by executor identity instead of a per-registration token, and queued checks not invalidated on cancel (StoreClone:204 and StoreClone:224).
- Hardcoded lists or patterns that mirror a set defined elsewhere (RELEASE_TAG versus RELEASE_VERSION, and the checkpoint namespaces versus PUSH_REF_SPECS) (StoreReferences:90 and StoreReferences:254).
- Weak tests: assertions on executor threads, snapshots taken after a mutation, fixtures that accept any key reference, and a namespace test that could not separate two failure causes.

Possible lesson: in JGit write paths of this repository, check every `RefUpdate.Result`, and peel tags before using them. Bind each lease to the state the payload was built on. Derive mirrored sets from their source. Tests for asynchronous code should assert on the JUnit thread. These classes slipped through implementation and the in-run quality gates, and the review bot caught them only after the PR was opened.
