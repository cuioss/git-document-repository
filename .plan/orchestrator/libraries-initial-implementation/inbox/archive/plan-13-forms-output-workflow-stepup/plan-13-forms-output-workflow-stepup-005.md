envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:25:29Z

## Candidate lesson: a light-lane 2-refine exemption survives the escalation to the deep lane

Signal: retrospective (status metadata, decision log, metrics).

What happened: phase-1-init routed the plan to the light lane with low confidence (change_type and compatibility unresolved). manage-status then recorded `phase_exemptions.2-refine` with the reason "light lane: phase-2-refine does not run; the collapsed envelope owns refine". One minute later the light-lane outline escalated to deep (trigger `explosion`: a 45-file git-forms surface over the cap of 25), 2-refine was re-opened and ran (its metrics row has `close_count: 2`). The exemption is still in `status.metadata` at the end of the plan, with a reason that is false for this plan.

Suggested rule: when `planning-lane` escalates light -> deep (the one-way ratchet), withdraw every phase exemption whose reason is the light lane, or mark it superseded with the escalation timestamp. A consumer that reads the exemption must not conclude that 2-refine did not run.

Component hint: plan-marshall:manage-status (planning-lane escalation and phase exemptions).
