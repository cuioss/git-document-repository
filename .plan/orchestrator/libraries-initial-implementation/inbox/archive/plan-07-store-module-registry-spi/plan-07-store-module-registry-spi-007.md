envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:24Z

# Candidate lesson: dispatched leaves repeatedly hit a documented-vs-installed executor mismatch

**Source:** orchestrator observation across the run's phase-5 and finalize dispatches
**Plan:** plan-07-store-module-registry-spi

## Observation

The subagent leaves hit the same mismatch between the documented executor surface (workflow docs, canonical
invocation blocks) and the installed executor more than once in this run. Each leaf rediscovers it, because a
leaf has no memory of the previous leaf's retry. The ledger `--changed-paths-file` drift (separate candidate)
is one concrete case.

## Candidate rule

When a leaf finds a documented call that the installed executor rejects, it should record the drift once
(a finding or inbox candidate) so the next leaf of the same plan does not repeat the failed call, and the
documentation is corrected at the source.
