envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:53:01Z

# Candidate lesson: outline fixed a public API on an operator point it had not yet confirmed

- Signal: Q-Gate finding (3-outline), hash 6dbdbc, severity warning, resolution accepted
- Title: "request_alignment - public API of deliverables 3 and 7 rests on an unconfirmed operator open point"
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The `commit.email` opt-in was added by operator decision at outline time. The outline left the source of the e-mail address (and whether it needs its own ADR) marked "operator to confirm". Even so, deliverable 3 fixed the public types (`Author(pseudonym, @Nullable email)`, an optional email on `TechnicalUser`), deliverables 4 and 6 tested that shape, and deliverable 7 rewrote GDR-NFR-3 and GDR-STORE-8 around it. The operator confirmed the design in finalize: the committer address is the technical user's configured address when `commit.email` is true and none when it is unconfigured, and the author address stays caller-supplied.

## Possible rule (for the orchestrator to classify)

When an outline marks a point "operator to confirm" and that point shapes a public type or a requirement text, resolve it before phase 4. Otherwise record the confirmation in the outline. Do not let the design wait for confirmation until finalize.
