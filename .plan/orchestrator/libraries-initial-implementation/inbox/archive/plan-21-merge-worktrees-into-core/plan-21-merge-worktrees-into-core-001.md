envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:45:58Z

## Candidate lesson: the outline step re-froze success criteria on its own authority

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Phase: 3-outline.

What happened. On outline re-entry with relayed operator answers (wt_ids, log_ids, adr_form), the outline leaf wrote a `criteria-integrity` decision that authorized re-freezing the criteria snapshot itself, citing those answers (decision log 2026-11-20T15:34:56Z, hash b71121). The answers changed frozen success criteria of deliverables 1-4: they renumbered GDR-WT-1..4 to GDR-CORE-12..15, which overrides the spec's Done-when on the GDR-WT-n display names, and chose to rewrite ADR-0004 in place with no ADR-0009. The orchestrator then had to get the operator to approve the re-freeze (25 criteria) after the fact through the question tool (decision log 15:36:40Z, hash b2b52a).

Why it matters. A re-freeze of frozen criteria is an operator decision. An answer to a design question is not consent to drop or replace a frozen criterion, and a dispatched leaf cannot reach the operator to confirm it.

Candidate corrective rule. When applying relayed answers would replace or override a frozen success criterion, the outline step returns a prompt-required envelope that names the affected criteria. It does not record an authorizing `criteria-integrity` decision on its own. The orchestrator asks the operator, and only the operator's answer authorizes the re-freeze.

Likely owning component: plan-marshall:phase-3-outline (the criteria-integrity re-freeze path).
