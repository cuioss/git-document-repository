envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:59:07Z

# Credit a green whole-reactor build on the same tree to an unmeasured module-tests step

- Source: plan-retrospective (chat-history, logging-gap and dispatch-audit aspects), plan-08-store-drafts-save
- Component: plan-marshall:phase-5-execute
- Category: improvement
- Confidence: high

## Context

The end-of-phase step `verify:module-tests` for git-store (`verify -pl git-store -am`) resolved to the orchestrator tier twice because it had never been measured. Envelopes 3 and 4 both returned `verification_handback`, although whole-reactor `verify -Ppre-commit` (and in envelope 3 a full `verify`) had already passed on the exact same tree. Each hand-back cost an extra orchestrator build and an extra re-entry dispatch (5-execute shows 2 `verification_handback` boundary rows and 4 closes). Afterwards the freshness check still reported `scope_cross_check: undetermined` (`required_coverage_unknown`).

## Root cause

The tier decision for a never-measured command does not consider that a broader build covering the same modules already passed on the same tree hash, and the build ledger does not record which modules a whole-reactor run covered.

## Proposed action

When a whole-reactor build of the same goal (or a superset) passed on the current tree hash, record the module-tests step as satisfied instead of handing it back; or seed the timing measurement for the module command from that run. Record the covered modules on the ledger row so the freshness scope check can decide.

## Evidence

- aspect: logging_gap_analysis — 5-execute termination causes include 2 verification_handback
- aspect: chat_history_analysis — envelopes 3 and 4 hand back verify -pl git-store -am as never measured, after green verify -Ppre-commit on the same commit
- aspect: execution_context_dispatch_audit — firing_comparison lists 4 unpaired execute verification rows (3 quality-gate, 1 module-tests)
