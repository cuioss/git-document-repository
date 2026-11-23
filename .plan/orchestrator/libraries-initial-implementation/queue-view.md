<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: RESTART HERE (2026-11-23, PLAN-10 analysed and landed on main via PR #63). RUNNING (operator-confirmed): PLAN-11 (plan-11-store-anchoring-signed) and PLAN-13 (plan-13-forms-output-workflow-stepup), both observed; PLAN-20 (leftover fixes 2) LAUNCHED on the operator's word, no plan directory observed yet: move to running once seen. Accepted overlaps: PLAN-11/13 on AGENTS.md and doc/README.adoc; PLAN-13/20 on AGENTS.md, general.adoc, pom.xml. When any ships: analyze (inbox drain, landing report), then land. Blocked: PLAN-14 (needs PLAN-13), PLAN-15 (PLAN-14), PLAN-16 (PLAN-15), PLAN-17 (PLAN-11); PLAN-22 held back until the operator names it (sequential after PLAN-11 on git-store files). OPEN ITEMS, the operator's decisions: (1) where PLAN-10's ten layer-3 minors in git-store go (Epic Open Defect 'PLAN-10 defects in git-store' NOT YET ROUTED; candidate PLAN-22 deliverable 4); (2) where PLAN-23's layer-3 findings in git-forms go (one MAJOR DataNode pointer-string amplification, five minor; NOT YET ROUTED); (3) the shared-documents collision rule (Watch); (4) PLAN-15 and PLAN-16 carry stale 'contradicted, not re-scoped' verdicts (PLAN-16's is the SaveStep gap PLAN-09 shipped): re-ground before emit. Stage nothing without the operator's word. Standing rules: scripts run from the repo root and exit 0 on error so read status:; lessons with --allow-foreign-store on add only; nothing launched, landed or staged as a fix plan without the operator's word; PR bodies end with the Claude Code line and the session URL. Open: 0.1.0 release question, stale .plan/project-architecture and empty skills_by_profile of git-store/git-forms, incomplete landings PLAN-01..08,10,12,18,19,21,23.
**Phase**: orchestrating
**Running**:
- PLAN-11 (WS-02) — plan=plan-11-store-anchoring-signed
- PLAN-13 (WS-03) — plan=plan-13-forms-output-workflow-stepup
**Queue** (staged, in order):
1. PLAN-14 (WS-03)
2. PLAN-15 (WS-04)
3. PLAN-16 (WS-04)
4. PLAN-17 (WS-05)
5. PLAN-22 (WS-02)
- PLAN-01 (WS-01) — plan=git-testing-fixtures — PR 23 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-01) — plan=git-core-import — PR 25 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-01) — plan=plan-03-git-worktrees-import — PR 30 — landing=landings/PLAN-03.md — status: shipped
- PLAN-04 (WS-01) — plan=git-core-incore-write-cas — PR 35 — landing=landings/PLAN-04.md — status: shipped
- PLAN-05 (WS-01) — plan=plan-05-git-core-merge-read-notes — PR 37 — landing=landings/PLAN-05.md — status: shipped
- PLAN-06 (WS-01) — plan=implement-plan-06-build-guards — PR 39 — landing=landings/PLAN-06.md — status: shipped
- PLAN-07 (WS-02) — plan=plan-07-store-module-registry-spi — PR 41 — landing=landings/PLAN-07.md — status: shipped
- PLAN-08 (WS-02) — plan=plan-08-store-drafts-save — PR 48 — landing=landings/PLAN-08.md — status: shipped
- PLAN-09 (WS-02) — plan=plan-09-store-release-index-listeners — PR 52 — landing=landings/PLAN-09.md — status: shipped
- PLAN-10 (WS-02) — plan=plan-10-store-signing-trailers-verify — PR 58 — landing=landings/PLAN-10.md — status: shipped
- PLAN-12 (WS-03) — plan=plan-12-forms-kinds-schema-layout — PR 54 — landing=landings/PLAN-12.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped
- PLAN-20 (WS-01) — status: launched
- PLAN-21 (WS-01) — plan=plan-21-merge-worktrees-into-core — PR 46 — landing=landings/PLAN-21.md — status: shipped
- PLAN-23 (WS-03) — plan=plan-23-forms-hardening — PR 59 — landing=landings/PLAN-23.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-11 | WS-02 | running | AGENTS.md; doc/README.adoc; doc/specification/git-store.adoc; git-store; git-store/ | plan-marshall |
| 2 | PLAN-13 | WS-03 | running | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; git-forms; git-forms/; pom.xml | plan-marshall |
| 3 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc; git-forms/ | plan-marshall |
| 4 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 5 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 6 | PLAN-17 | WS-05 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 7 | PLAN-20 | WS-01 | launched | AGENTS.md; README.adoc; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-testing.adoc; git-core/doc/LogMessages.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/main/java/de/cuioss/git/document/core/worktrees/; git-core/src/main/resources/META-INF/native-image/; git-core/src/test/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/worktrees/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/; pom.xml | plan-marshall |
| 8 | PLAN-22 | WS-02 | staged | AGENTS.md; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store/doc/LogMessages.adoc; git-store/pom.xml; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/main/java/module-info.java; git-store/src/test/java/de/cuioss/git/document/store/ | plan-marshall |
