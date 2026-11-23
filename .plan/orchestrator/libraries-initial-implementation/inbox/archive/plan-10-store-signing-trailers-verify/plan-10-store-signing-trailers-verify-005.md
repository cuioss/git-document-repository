envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:35:11Z

# Candidate lesson: specification statements outran the mechanism (signing contract, failure side effects, history policy, trust prerequisite)

Source: review-bot findings, all resolution fixed: ac530b (git-store.adoc:678, TASK-17), 66d4be (git-store.adoc:684, TASK-18), 5b9135 (git-store.adoc:776, TASK-18), f632df (review body, allowed-signers trust boundary, TASK-18).

Component: doc/specification/git-store.adoc (GDR-STORE-12/13, signature verification section); VerifySignatures Javadoc

## What happened

Four statements in the specification did not match what the code does or left a contract open:

- The Outcomes bullet said a failed save leaves the branches as they are, but the attempt's fetch can already have updated the clone.
- One bullet required every commit to be signed while another required unsigned draft commits.
- Nothing said how verification treats commits written before signing existed (they are reported UNSIGNED and the CI command exits 1).
- The requirement "allowed signers never come from the repository" had no mechanism; VerifySignatures reads whatever path it is given.

## Candidate rule

For each requirement a change implements, check every "stays", "never", "every" or "is refused" sentence against the file that would have to enforce it. Where no mechanism exists, rewrite the sentence to state the real behaviour or the operator prerequisite. When a store starts signing, state the policy for history written before signing in the same change.
