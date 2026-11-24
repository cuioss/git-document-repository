envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:10Z

# Candidate lesson: Q-Gate 4-plan - undeclared-empty skills_by_profile for git-store (implementation)

Source: Q-Gate finding 4ad387 (phase 4-plan, resolution accepted).

Observation: module `git-store` has an undeclared-empty `skills_by_profile.implementation`. Phase 4-plan created the task with persona-resolved skills only and raised a triage finding. The finding was accepted as informational with no code change, because architecture enrichment was out of scope for the plan.

Possible lesson: `git-store` (and maybe other modules of this reactor) needs a one-off architecture enrichment, or a deliberate `minimal: true` declaration, so that every plan touching the module stops raising this finding. The finding repeats across plans and is triaged as noise every time.

Classification is up to the orchestrator.
