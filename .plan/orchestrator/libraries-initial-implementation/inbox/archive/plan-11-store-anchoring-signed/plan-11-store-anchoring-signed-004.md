envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:32Z

# Candidate lesson: Q-Gate 3-outline - open question left unresolved between Risks and the deliverable

Source: Q-Gate finding d052b2 (phase 3-outline, resolution taken_into_account).

Observation: the outline's Risks section settled the question by adding the subject's commit time as a parameter of `AnchorProvider.obtain`. Deliverable 1's per-file change still said the signature would change only "if the open question resolves that way". Tasks cannot be planned against two readings at once. The implementation chose the parameter, and `AnchorEngine` passes the commit time.

Possible lesson: once the outline settles an open question (for example in Risks), write the decision into every affected deliverable's change description. Leaving a conditional "if X resolves" phrasing makes the outline internally inconsistent.
