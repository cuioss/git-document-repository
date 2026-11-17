# WS-04: Directory and catalogs — git-directory

epic: libraries-initial-implementation

> Charter document for one workstream. See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier
> contract.

## Charter

Create `git-directory` (module `de.cuioss.git.document.directory`) on `git-forms` and implement GDR-DIR-1..7: the
catalogs of roles and teams, the default person directory `git` (the `PersonDirectory` provider of GDR-STORE-19),
first login, reference resolution, periodic confirmation, erasure and prefilled entries. It closes when every
`GDR-DIR` requirement is implemented and tested.

## Scope

- In scope: the new `git-directory/` module, the root `pom.xml` module entry, the layering guard entry,
  `doc/specification/git-directory.adoc`, the state columns of `doc/concepts/module-structure.adoc` and `AGENTS.md`.
- Out of scope: the `PersonDirectory` interface itself (PLAN-07), application-side identity handling.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-15-directory-catalogs-persons | staged | module, catalogs, person directory `git`, resolution |
| PLAN-16-directory-login-lifecycle | staged | first login, confirmation, erasure, prefilled entries |

## Sequencing and Surface Notes

- PLAN-15 → PLAN-16, strictly sequential (same unresolved-new-module caveat as WS-02).
- PLAN-15 needs PLAN-12 (schema-driven entries, YAML codec) and PLAN-14 (catalog references are typed links).
