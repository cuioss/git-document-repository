envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:09:47Z

# Candidate lesson: dispatch-boundary rows carry no step_id, so no dispatch can be paired with its step

Source: plan-retrospective (finalize), execution-context-dispatch-audit aspect (firing_comparison) and log-analysis (dispatch_boundaries).

The facts:
- 24 dispatch-boundary rows were recorded: 1 in 4-plan, 7 in 5-execute and 16 in 6-finalize. All 24 are keyless (`keyless_boundary_rows` equals `boundary_rows` in every phase).
- 6-finalize has 20 `execution_log` rows that do carry a step key. Because the boundary rows have no key, 0 of 20 were paired. The same is true in 5-execute (2 `verify:module-tests` rows, 0 paired).
- All 24 rows also leave the four token-split columns unmeasured, so the context-position cost is unmeasured for all 24 rows.
- The finalize boundary rows add up to about 2.69M tokens, and metrics.md reports 6-finalize as `unknown`. Without a step key, nobody can tell which finalize steps spent that, or how much was the 427K of retryable `blocked_session_restart` spend.

Observation: `record-dispatch-boundary` is called without the step identifier the firing comparison joins on, so per-step finalize cost is unattributable, even though finalize is the costliest part of this plan.

Candidate rule (for the orchestrator to classify): every finalize and execute dispatch site passes its `step_id` (or `task_number`) to `record-dispatch-boundary`. The recorder could refuse, or at least warn about, a row from a known step-dispatching role that has no key.

Not a duplicate of messages 001-016.

Context: plan plan-20-leftover-fixes-2.
