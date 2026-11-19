<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: PLAN-05 (PR #37, 14269fe; landings/PLAN-05.md) shipped; inbox empty; nothing is running. PLAN-06 (build-guards, the last foundation plan) is emitted and awaits the operator's launch confirmation (emit is not running; record launched/running only on the operator's word). PLAN-07..17 chain behind PLAN-06. PLAN-20 (leftover-fixes-2, second collection plan) is staged and held back until the operator releases it: it collects the layer-3 defects of PLAN-04, PLAN-05 and PLAN-19 and the git-testing leftovers, and it changes behaviour of public git-core and git-worktrees operations, so put to the operator whether to release it before the 0.1.0 release that follows PLAN-06 (a Maven Central release cannot be withdrawn). After PLAN-06 lands: run analyze, then next.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-06 (WS-01)
2. PLAN-07 (WS-02)
3. PLAN-08 (WS-02)
4. PLAN-09 (WS-02)
5. PLAN-10 (WS-02)
6. PLAN-11 (WS-02)
7. PLAN-12 (WS-03)
8. PLAN-13 (WS-03)
9. PLAN-14 (WS-03)
10. PLAN-15 (WS-04)
11. PLAN-16 (WS-04)
12. PLAN-17 (WS-05)
13. PLAN-20 (WS-01)
- PLAN-01 (WS-01) — plan=git-testing-fixtures — PR 23 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-01) — plan=git-core-import — PR 25 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-01) — plan=plan-03-git-worktrees-import — PR 30 — landing=landings/PLAN-03.md — status: shipped
- PLAN-04 (WS-01) — plan=git-core-incore-write-cas — PR 35 — landing=landings/PLAN-04.md — status: shipped
- PLAN-05 (WS-01) — plan=plan-05-git-core-merge-read-notes — PR 37 — landing=landings/PLAN-05.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-06 | WS-01 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/specification/general.adoc; git-core/pom.xml; git-core/src/main/resources/; git-worktrees/pom.xml; git-worktrees/src/main/resources/; pom.xml | plan-marshall |
| 2 | PLAN-07 | WS-02 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-store.adoc; pom.xml | plan-marshall |
| 3 | PLAN-08 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc | plan-marshall |
| 4 | PLAN-09 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc | plan-marshall |
| 5 | PLAN-10 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc; pom.xml | plan-marshall |
| 6 | PLAN-11 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/git-store.adoc | plan-marshall |
| 7 | PLAN-12 | WS-03 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 8 | PLAN-13 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 9 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc | plan-marshall |
| 10 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 11 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 12 | PLAN-17 | WS-05 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 13 | PLAN-20 | WS-01 | staged | AGENTS.md; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-testing.adoc; doc/specification/git-worktrees.adoc; git-core/doc/LogMessages.adoc; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/; git-worktrees/doc/LogMessages.adoc; git-worktrees/src/main/java/de/cuioss/git/document/worktrees/; git-worktrees/src/test/java/de/cuioss/git/document/worktrees/ | plan-marshall |
