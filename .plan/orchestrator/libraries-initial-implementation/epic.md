# Epic: Initial implementation of the libraries

slug: libraries-initial-implementation

> Hand-written narrative for one epic under `.plan/orchestrator/libraries-initial-implementation/`. The
> layout and authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. The ledger JSON files
> (`status.json`, the `queue/{PLAN-ID}.json` rows) and `resume_anchor.md` are the machine
> authority; any statement here that conflicts with them is stale prose.
>
> START HERE and the Ordered Queue are not in this file. They live in the generated,
> git-tracked `queue-view.md` next to it (see the Persist / Stop-Resume Contract in that
> standard). `queue-view.md` is never hand-edited. A merge conflict in it is never merged by
> hand: merge the source files, run
> `python3 .plan/execute-script.py plan-marshall:plan-orchestrator:orchestrator regenerate-view --slug libraries-initial-implementation`
> on the merged tree, and `git add` the result.

## Vision

Turn the documented design of `git-document-repository` (`doc/concepts/`, `doc/adr/`, `doc/specification/`) into
working code: first the JGit foundation (`git-core`, `git-worktrees`, `git-testing`), taken over from
`pm-provider-git` in `~/git/pm-mcp-core` and extended by the new requirements, then built and released as 0.1.0;
then the aspect modules of the document store in the order `doc/concepts/module-structure.adoc` § Order gives —
`git-store`, `git-forms`, `git-directory`, `git-timestamp`. It is too large for one plan because it spans seven
modules and roughly fifty requirements (`GDR-GEN`, `GDR-NFR`, `GDR-CORE`, `GDR-WT`, `GDR-TEST`, `GDR-STORE`,
`GDR-FORMS`, `GDR-TSA`, `GDR-DIR`). Done means every `GDR` requirement of the library specification is implemented and
tested, the reactor holds all seven modules with the layering guard of GDR-GEN-1 enforcing the dependency graph, and
the documentation's "scaffold / planned / not yet implemented" statements are replaced by the actual state.

Out of scope: `doc/app/` (CISOs-Friend), which gets its own orchestrator epic, and the switch-over of
`pm-provider-git` in `pm-mcp-core`, which is work in that repository.

## Queue annotations

- PLAN-01 — First plan. Launch only after the ledger PR (`chore/orchestrator-ledger-init`) is merged, so its
  worktree, branched from `main`, carries the specs.
- PLAN-03 / PLAN-04 — May run side by side after PLAN-02 (git-worktrees vs git-core; shared only `AGENTS.md`).
- PLAN-06 — Last foundation plan. After it lands: the operator runs `/release` for 0.1.0 (not a queue row, see
  Decisions), then the aspect modules start.
- PLAN-07..PLAN-11 — Strictly sequential: all edit `git-store/`, which the surface reader cannot see until PLAN-07
  creates it. The same holds for PLAN-12..14 (`git-forms/`), PLAN-15..16 (`git-directory/`) and PLAN-17
  (`git-timestamp/`).
- PLAN-12 — Can start once PLAN-08 has landed, in parallel with PLAN-09..11 (multi-file overlap on `pom.xml`,
  `general.adoc`, `AGENTS.md` → the gate puts it to the operator).
- PLAN-10, PLAN-12, PLAN-13, PLAN-17 — Each opens with an owner decision on a third-party library; expect a question
  early in the plan's refine phase.

## Decisions

- 2026-11-17 — Epic scope is every library module of this repository: foundation, the 0.1.0 release, and the four
  aspect modules. `doc/app/` is excluded (separate orchestrator). The `pm-mcp-core` switch-over is excluded; it is
  tracked as a watch only. Alternatives offered: foundation + release only; all + the cross-repo switch-over.
  Operator choice.
- 2026-11-17 — `parallelization_scope` = 3 (operator). The foundation plans mostly serialise on `git-core` and the
  root `pom.xml` anyway; the scope pays off once the aspect modules diverge.
- 2026-11-17 — Dependencies approved by the owner for the foundation: JGit 7.8.0 (`git-core`, and directly in
  `git-testing`), the test stack (`cui-test-generator`, JUnit 5, EasyMock, `cui-test-juli-logger`), Lombok and
  `cui-java-tools` (both used by the `pm-provider-git` code being moved). Every third-party dependency of the aspect
  modules stays OPEN and needs the owner's approval inside the plan that introduces it: the SSH signature encoding
  (`org.eclipse.jgit.ssh.apache`, GDR-STORE-12), the YAML library, the JSON Schema validator and the template engine
  (`git-forms`), the RFC 3161 client (`git-timestamp`), and any heap-measuring tool for GDR-NFR-1.
- 2026-11-17 — The owner releases the `pm-provider-git` code under the Apache License 2.0 (this closes open
  question 2 of `doc/clients/pm-mcp-core.adoc`). The code is taken over as a **plain copy without history**, its
  provenance (source repository, path and commit) named in the commit message. This deviates from "with history" in
  `doc/concepts/module-structure.adoc` § Order and `doc/clients/pm-mcp-core.adoc`; the import plans update both
  documents. Alternative rejected by the operator: filtering the source history and merging it in.
- 2026-11-17 — The 0.1.0 release is NOT a queue row. It is an operator act through the project skill `/release`
  (`.claude/skills/release/SKILL.md`), a deliberate dispatch from `main` once the foundation modules carry code
  (AGENTS.md § Releases). It sits between WS-01 and WS-02 in the narrative order; no aspect-module plan depends on it
  technically, so the queue does not block on it.
- 2026-11-17 — Decomposed into five workstreams cut by module along the dependency graph (WS-01 foundation, WS-02
  `git-store`, WS-03 `git-forms`, WS-04 `git-directory`, WS-05 `git-timestamp`) and 17 plans in dependency order.
  Alternative considered: cutting by concern across modules (e.g. one "anchoring" workstream spanning store and
  timestamp) — rejected because every plan would then touch two not-yet-existing modules the gate cannot see.
- 2026-11-17 — Split guard: PLAN-02 and PLAN-08 carry five deliverables and proceed unsplit. PLAN-02 is one move of the
  git-core half of `pm-provider-git` (a split would separate moved tests from moved code); PLAN-08 is one save
  algorithm whose parts share one commit path and the GDR-NFR-2 acceptance test.
- 2026-11-17 — Non-functional placement: GDR-NFR-1 in PLAN-09, GDR-NFR-2 in PLAN-08, GDR-NFR-4 (foundation) and the
  GDR-GEN-1 guard in PLAN-06; GDR-GEN-2/3/4 and GDR-NFR-3 are standing constraints of every spec. Every open
  third-party choice is the first deliverable of the plan that needs it and goes to the owner there.
- 2026-11-17 — Resolved the git-ignored ledger (found at decompose: `.gitignore` `.plan/*` hid `.plan/orchestrator/`).
  `.gitignore` now un-ignores `.plan/orchestrator/` and `.plan/archived-orchestrators/` and keeps their `*/logs/`
  local; the ledger lands on `main` through `chore/orchestrator-ledger-init`, at the operator's instruction.

## Open Defects

## Watches

- The `pm-mcp-core` switch-over (`doc/clients/pm-mcp-core.adoc` § Order of the Switch-Over, step 2) follows the
  0.1.0 release and is work in `~/git/pm-mcp-core`. Its open question 3 (JGit in the native image of `pm-mcpd`
  against the first SNAPSHOT) is also the only verification of GDR-NFR-4 until this repository has a native test lane.
  — re-check after the 0.1.0 release.
- New-module surfaces are unresolved until the module exists (`git-store/`, `git-forms/`, `git-directory/`,
  `git-timestamp/` are reported in `corpus surfaces` `unresolved[]`). Once PLAN-07 / PLAN-12 / PLAN-15 / PLAN-17 land
  the directory, the same entries resolve and the gate sees the in-module overlaps by itself. — re-check
  `corpus surfaces` at each module-creating landing.
- Plans after PLAN-08 assume `git-store`'s save exposes a per-attempt hook (regenerating output, PLAN-13; first-login
  checks, PLAN-16). If PLAN-08 lands without one, that is a store gap to stage, not to absorb in a forms or directory
  plan. — re-check at the PLAN-08 landing.
