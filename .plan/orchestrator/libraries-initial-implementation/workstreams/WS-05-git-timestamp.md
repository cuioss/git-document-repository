# WS-05: RFC 3161 anchoring — git-timestamp

epic: libraries-initial-implementation

> Charter document for one workstream. See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier
> contract.

## Charter

Create the optional `git-timestamp` (module `de.cuioss.git.document.timestamp`) on `git-store` and implement
GDR-TSA-1/2: the anchor provider `rfc3161` and the verification of its tokens. Its RFC 3161 client library is open and
needs the owner's approval. It closes when both `GDR-TSA` requirements are implemented and tested without network
access.

## Scope

- In scope: the new `git-timestamp/` module, the root `pom.xml` module entry and approved managed dependency, the
  layering guard entry, `doc/specification/git-timestamp.adoc`, the state columns of
  `doc/concepts/module-structure.adoc` and `AGENTS.md`.
- Out of scope: which subjects are anchored, notes and pushes (all `git-store`, PLAN-11).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-17-timestamp-rfc3161 | staged | module, `rfc3161` provider, token verification |

## Sequencing and Surface Notes

- Needs the `AnchorProvider` interface and the store outcome list (PLAN-07); best run after PLAN-11 so the provider is
  exercised through the real anchoring path. Disjoint from WS-03/WS-04 apart from the shared root `pom.xml`,
  `doc/concepts/module-structure.adoc` and `AGENTS.md` — a multi-file overlap the gate puts to the operator.
