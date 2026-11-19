envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:44:05Z

# 56-minute CodeRabbit rate-window wait gated a one-line Sonar test fix

Source signal: automatic-review step state, plan git-core-import, PR 25, loop-back iteration 4 (granted).

## What happened

Iteration 4 committed only a one-line Sonar S5778 test fix (19b2ea5). The re-run of `plan-marshall:automatic-review`
then hit the CodeRabbit review rate window. The plan waited from 12:35:50Z to 13:32:31Z (about 3400 s, bounded by
5400 s) before automatic-review could run again. That review produced nothing new, and branch-cleanup followed.
Earlier rounds on the same PR had already used up the bot quota.

## Why it matters

The wait was almost an hour of wall-clock time on a change that had no review-relevant surface. It also held the
plan's place in the epic queue. Bot quota is shared across plans, so a non-converging review loop on one PR
(see the sibling candidate about review rounds) delays every later plan too.

## Candidate corrective direction

- Let the finalize loop skip or downgrade the bot re-review when the delta since the last reviewed commit is trivial
  (test-only, below a line threshold, or a Sonar-rule-only fix), and rely on CI plus the Sonar round-trip for it.
- Or batch the Sonar fix into the previous round's commit when both are known before the push, so one bot review
  covers both.
- Surface the remaining bot quota before a loop-back is granted, so the operator knows the iteration costs about an
  hour of waiting.
