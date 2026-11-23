envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:26Z

# Candidate lesson: a reader-only bound broke the codec round trip (review bot caught it)

Signal: automated review (PR #59, CodeRabbit inline finding 908e3b on YamlDocumentCodec.java:106, resolution fixed via TASK-19, commit 8ea86a1).

## What happened

At outline the owner answered q3 as "number bounds in YamlReader only, in-memory numbers stated unbounded" (decision log 07:38:07). The plan implemented exactly that. CodeRabbit then showed the consequence: `encode` accepted a `BigDecimal("1e1001")` or `BigInteger.TEN.pow(1000)`, wrote it, and `decode` refused the bytes as MALFORMED, which breaks the documented promise that `decode(encode(v))` succeeds for every value `encode` accepts.

The first triage disposition kept the reader-only decision and only documented the exception. The owner then changed TASK-19 from "document the gap" to "make encode refuse numbers beyond the reader bounds" (decision log 10:52:55). The fix landed with `YamlDocumentCodecTest.NumberBounds`.

Same review round: four Sonar findings (S5778, S6213, S3776, S1192) were fixed inline in db68f9a. They are routine and are listed only so the round is complete.

## Candidate rule

When a plan places an input bound on one side of a symmetric pair (reader/writer, decode/encode, parse/format), the outline must check the pair's round-trip contract. Either the other side enforces the same bound, or the contract is changed explicitly. A one-sided bound is a decision that needs a stated round-trip consequence, not just a per-side answer.

Scope: git-forms codec design. Possibly a general outline check for the epic's other codec/reader plans.
