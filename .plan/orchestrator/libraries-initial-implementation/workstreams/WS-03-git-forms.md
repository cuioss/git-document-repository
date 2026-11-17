# WS-03: Schema-driven documents — git-forms

epic: libraries-initial-implementation

> Charter document for one workstream. See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier
> contract.

## Charter

Create `git-forms` (module `de.cuioss.git.document.forms`) on `git-store` and implement GDR-FORMS-1..9: kinds of
documents as configuration, schema validation, the YAML data layout and codec, generated AsciiDoc output, workflow,
deadlines, step-up per transition, typed links and running numbers. Its three third-party libraries (YAML, JSON Schema
validator, template engine) are open and need the owner's approval inside the plans. It closes when every
`GDR-FORMS` requirement is implemented and tested.

## Scope

- In scope: the new `git-forms/` module, the root `pom.xml` (module entry, approved managed dependencies), the
  layering guard entry, `doc/specification/git-forms.adoc` (closing its open dialect / library questions), the state
  columns of `doc/concepts/module-structure.adoc` and `AGENTS.md`.
- Out of scope: changes to `git-store` beyond what a forms plan proves missing (such a gap is reported, not absorbed);
  catalogs and persons (WS-04).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-12-forms-kinds-schema-layout | staged | module, kinds, schema validation, YAML layout and codec |
| PLAN-13-forms-output-workflow-stepup | staged | generated output, workflow, step-up |
| PLAN-14-forms-deadlines-links-numbers | staged | deadlines, typed links, running numbers |

## Sequencing and Surface Notes

- Strictly sequential inside the module (same unresolved-new-module caveat as WS-02): PLAN-12 → PLAN-13 → PLAN-14.
- PLAN-12 needs the save of PLAN-08; PLAN-13 needs the release of PLAN-09 and the trailers of PLAN-10 (step-up reads
  `Auth-Context` / `Auth-Time`).
