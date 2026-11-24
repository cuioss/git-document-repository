envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:16Z

# Candidate lesson: Q-Gate 4-plan - undeclared-empty skills_by_profile for git-store (module_testing)

Source: Q-Gate finding e95926 (phase 4-plan, resolution accepted).

Observation: module `git-store` has an undeclared-empty `skills_by_profile.module_testing`. The test task was created with persona-resolved skills only. The finding was accepted as informational with no code change.

This is the module_testing twin of the implementation-profile finding 4ad387 from the same plan. Both come from one root cause: `git-store` has no enriched or deliberately minimal skill profiles. The orchestrator may want to fold the two into one candidate.
