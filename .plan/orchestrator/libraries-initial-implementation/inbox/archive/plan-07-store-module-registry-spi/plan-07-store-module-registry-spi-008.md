envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:25Z

# Candidate lesson: simplify and the pre-push gate re-fired four times on head changes

**Source:** orchestrator observation, confirmed by the work log (finalize-step-simplify and
pre-push-quality-gate completed at 09:51/09:54, 10:34/10:36 after rebase onto origin/main, 11:31/11:33 after
loop-back 1, and again after loop-back 2)
**Plan:** plan-07-store-module-registry-spi

## Observation

Both head-dependent steps re-fire on every HEAD change: once for the rebase onto origin/main and once per
loop-back iteration. Each pre-push gate is a full `verify -Ppre-commit` (about 1.5 to 2.5 minutes here), and
each simplify is a dispatched agent.

## Candidate rule

Ordering the rebase before the first simplify / pre-push run, and batching review fixes into fewer loop-backs,
would cut the repeated runs. A further option: skip simplify on a re-fire when the new head only adds commits
already covered by a fresh simplify pass of the fix diff.
