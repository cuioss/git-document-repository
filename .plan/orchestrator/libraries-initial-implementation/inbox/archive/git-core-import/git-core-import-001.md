envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:43:15Z

# Review-bot rounds did not converge: each fix batch produced a fresh comment wave

Source signal: automatic-review (pr-comment findings remediated in-run), plan git-core-import, PR 25.

## What happened

The review loop needed four triage rounds on PR 25: 23, 27, 1 and 1 actionable findings. The third iteration
reached the 3/3 loop-back cap, and a fourth iteration had to be granted explicitly. The second round was larger
than the first. The bots re-reviewed the fix commits and raised new comments on code the fix batch had just
touched (GitOutcome / JGitRepositoryOperations push, fetch, redirect and credential handling), not only on lines
they had flagged before.

## Why it matters

Each round costs a full cycle of simplify, pre-push quality gate, push, CI verify, automatic-review and Sonar, plus
any bot quota wait. A loop that only ends when the bots run out of things to say is unbounded by design. The loop-back
cap then becomes the real stopping rule, and lifting it takes an operator decision.

## Candidate corrective direction (for the orchestrator to judge)

- Before the first push of a large security-sensitive takeover, run a local review pass that applies the same lenses
  the bots use: credential and PII leakage in results and diagnostics, remote-policy enforcement on every transport
  path including redirects, lease and up-to-date ref semantics, and test portability on non-POSIX file systems.
  The round-1 fix tasks (TASK-010..020) show these were the clusters the bots found.
- When fixing a batch, sweep the same cluster across every sibling call site instead of only the flagged line, so
  round N+1 does not find the next instance of the same defect.
- Consider budgeting the loop-back cap per PR size, or have the plan surface "new-comment rate per round" so a
  non-converging loop is visible before the cap is hit.
