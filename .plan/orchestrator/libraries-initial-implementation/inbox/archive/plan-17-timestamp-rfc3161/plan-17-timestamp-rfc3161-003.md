envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:26Z

component=plan-marshall:automatic-review
category=bug
signal=automated-review
shape=actionable

# Merge barrier counts an earlier bot review as participation after the bot's quota ran out

## What happened

In plan-17-timestamp-rfc3161 (PR #74), CodeRabbit's free-review quota ran out mid-run. The last three rounds of fixes were never reviewed by it, but the merge barrier still reported `review_complete_after_handling` because the bot's earlier review counted as participation. The fixes merged without the bot review the barrier claims to guarantee.

## Corrective action

- Bind bot participation to the reviewed HEAD: a review on an older commit should not satisfy the barrier for a newer head.
- Detect the quota or rate-limit reply (CodeRabbit posts a "rate limit exceeded" / "review limit reached" comment) and report a distinct state such as `review_unavailable_quota`, instead of folding it into completion.
- When that state is reported, require an explicit operator decision (wait, re-trigger later, or waive) rather than passing the barrier silently.

## Impact

Three rounds of changes merged without the automated review the gate reported as complete.
