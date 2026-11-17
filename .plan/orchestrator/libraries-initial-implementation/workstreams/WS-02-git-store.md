# WS-02: Document store — git-store

epic: libraries-initial-implementation

> Charter document for one workstream. See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier
> contract.

## Charter

Create `git-store` (module `de.cuioss.git.document.store`) on `git-core` and implement GDR-STORE-1..19 plus the
store-level non-functional requirements GDR-NFR-1 (index footprint) and GDR-NFR-2 (concurrent writers): the registry,
the three service interfaces, drafts and the explicit save, release, the index, post-commit listeners, codecs, the
commit signature and identity trailers, signature verification, and the anchoring with the own timestamp `signed`. It
closes when every `GDR-STORE` requirement is implemented and tested.

## Scope

- In scope: the new `git-store/` module, the root `pom.xml` (module entry, managed dependencies the owner approves),
  the layering guard entry of PLAN-06, `doc/specification/git-store.adoc`, the state columns of
  `doc/concepts/module-structure.adoc` and `AGENTS.md`.
- Out of scope: the YAML codec and everything schema-driven (WS-03), the `git` person directory provider (WS-04), the
  `rfc3161` provider (WS-05).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-07-store-module-registry-spi | staged | module, registry, configuration, codec interface, service interfaces, store outcomes |
| PLAN-08-store-drafts-save | staged | drafts, explicit save, conflicts, expected blob ids, expiry, author/committer |
| PLAN-09-store-release-index-listeners | staged | release, in-memory index, post-commit listeners |
| PLAN-10-store-signing-trailers-verify | staged | commit signature, identity trailers, signature verification |
| PLAN-11-store-anchoring-signed | staged | anchor provider contract, checkpoints, proofs as notes, own timestamp `signed` |

## Sequencing and Surface Notes

- Strictly sequential inside the module: every plan edits `git-store/`, which does not exist at HEAD, so the
  disjointness gate cannot see that overlap (a path under a not-yet-existing top-level directory is unresolved by the
  surface reader). Order: PLAN-07 → PLAN-08 → PLAN-09 → PLAN-10 → PLAN-11.
- PLAN-11 needs release tags (PLAN-09) and the SSH signature format (PLAN-10).
