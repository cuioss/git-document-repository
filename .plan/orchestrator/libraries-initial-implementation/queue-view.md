<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: PLAN-08 shipped (PR #48, c1e02fc, landings/PLAN-08.md); inbox empty. EMITTED, awaiting the operator's confirmed launch: PLAN-09 (git-store release, index, listeners + per-attempt save step) and PLAN-12 (git-forms module) together (overlap on AGENTS.md, doc/README.adoc, general.adoc accepted). On 'launched'/'#N is running': record it. After each lands: analyze, then next (PLAN-10 after PLAN-09; PLAN-13 needs 12, 09, 10; PLAN-17 after PLAN-11). PLAN-20 held back (8 deliverables, deliverable 8 collides with PLAN-09 on ExplicitSave/StoreWriter: release after PLAN-09 or split). Open: 0.1.0 release question, stale .plan/project-architecture, incomplete landings PLAN-01..08,18,19,21.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-09 (WS-02)
2. PLAN-10 (WS-02)
3. PLAN-11 (WS-02)
4. PLAN-12 (WS-03)
5. PLAN-13 (WS-03)
6. PLAN-14 (WS-03)
7. PLAN-15 (WS-04)
8. PLAN-16 (WS-04)
9. PLAN-17 (WS-05)
10. PLAN-20 (WS-01)
- PLAN-01 (WS-01) — plan=git-testing-fixtures — PR 23 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-01) — plan=git-core-import — PR 25 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-01) — plan=plan-03-git-worktrees-import — PR 30 — landing=landings/PLAN-03.md — status: shipped
- PLAN-04 (WS-01) — plan=git-core-incore-write-cas — PR 35 — landing=landings/PLAN-04.md — status: shipped
- PLAN-05 (WS-01) — plan=plan-05-git-core-merge-read-notes — PR 37 — landing=landings/PLAN-05.md — status: shipped
- PLAN-06 (WS-01) — plan=implement-plan-06-build-guards — PR 39 — landing=landings/PLAN-06.md — status: shipped
- PLAN-07 (WS-02) — plan=plan-07-store-module-registry-spi — PR 41 — landing=landings/PLAN-07.md — status: shipped
- PLAN-08 (WS-02) — plan=plan-08-store-drafts-save — PR 48 — landing=landings/PLAN-08.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped
- PLAN-21 (WS-01) — plan=plan-21-merge-worktrees-into-core — PR 46 — landing=landings/PLAN-21.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-09 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store; git-store/ | plan-marshall |
| 2 | PLAN-10 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store; git-store/; pom.xml | plan-marshall |
| 3 | PLAN-11 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/git-store.adoc; git-store; git-store/ | plan-marshall |
| 4 | PLAN-12 | WS-03 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 5 | PLAN-13 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 6 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc | plan-marshall |
| 7 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 8 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 9 | PLAN-17 | WS-05 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 10 | PLAN-20 | WS-01 | staged | AGENTS.md; README.adoc; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-store.adoc; doc/specification/git-testing.adoc; git-core/doc/LogMessages.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/main/java/de/cuioss/git/document/core/worktrees/; git-core/src/main/resources/META-INF/native-image/; git-core/src/test/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/worktrees/; git-store/pom.xml; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/test/java/de/cuioss/git/document/store/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/; pom.xml | plan-marshall |
