envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:14Z

component=pm-dev-java:java-core
category=improvement
signal=qgate/sonar
shape=actionable

# Signed-byte bitwise operations without an explicit mask fail the Sonar reliability gate late

## What happened

In plan-17-timestamp-rfc3161 (PR #74), the Sonar quality gate failed on `new_reliability_rating` because of one BUG, java:S3034: a bitwise operation on a signed `byte` without an explicit `& 0xFF` mask (DER/ASN.1 byte handling in the RFC 3161 code). Nothing earlier in the pipeline flagged it, so the fix needed a full extra finalize loop-back round (fix, push, CI, Sonar, re-review).

## Corrective action

- When writing byte-level parsing or encoding code in Java (DER, ASN.1, binary protocols), always mask a `byte` before combining it with `|`, `<<` or `+`: `(b & 0xFF)`, never a bare `b`.
- Catch the rule class before the push: either an implementation-time check for the S3034 pattern on byte-handling code, or a self-review step that scans the diff for bitwise operations on `byte` values. A single reliability BUG fails the gate on its own, so this rule is worth checking ahead of time instead of waiting for Sonar.

## Impact

One extra finalize round (CI plus Sonar plus bot re-review) for a one-line fix.
