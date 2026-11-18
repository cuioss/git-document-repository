<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: PLAN-01 shipped (PR #23) and reconciled; inbox drained. PLAN-02 (git-core-import) emitted, awaiting operator-confirmed launch (queue --transition PLAN-02 --status launched, then running when started). PLAN-03/04 follow PLAN-02. PLAN-18 (leftover-fixes) is a collection plan held back until the operator releases it. Ledger lands through the land verb.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-02 (WS-01)
2. PLAN-03 (WS-01)
3. PLAN-04 (WS-01)
4. PLAN-05 (WS-01)
5. PLAN-06 (WS-01)
6. PLAN-07 (WS-02)
7. PLAN-08 (WS-02)
8. PLAN-09 (WS-02)
9. PLAN-10 (WS-02)
10. PLAN-11 (WS-02)
11. PLAN-12 (WS-03)
12. PLAN-13 (WS-03)
13. PLAN-14 (WS-03)
14. PLAN-15 (WS-04)
15. PLAN-16 (WS-04)
16. PLAN-17 (WS-05)
17. PLAN-18 (WS-01)
- PLAN-01 (WS-01) — plan=git-testing-fixtures — PR 23 — landing=landings/PLAN-01.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-02 | WS-01 | staged | AGENTS.md; doc/clients/pm-mcp-core.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/main/java/module-info.java; git-core/src/main/resources/; git-core/src/test/java/; pom.xml | plan-marshall |
| 2 | PLAN-03 | WS-01 | staged | AGENTS.md; doc/clients/pm-mcp-core.adoc; doc/specification/git-worktrees.adoc; git-worktrees/pom.xml; git-worktrees/src/main/java/de/cuioss/git/document/worktrees/; git-worktrees/src/main/java/module-info.java; git-worktrees/src/test/java/ | plan-marshall |
| 3 | PLAN-04 | WS-01 | staged | doc/specification/git-core.adoc; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/test/java/ | plan-marshall |
| 4 | PLAN-05 | WS-01 | staged | AGENTS.md; doc/specification/git-core.adoc; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/test/java/ | plan-marshall |
| 5 | PLAN-06 | WS-01 | staged | AGENTS.md; README.adoc; doc/specification/general.adoc; git-core/pom.xml; git-core/src/main/resources/; git-worktrees/pom.xml; git-worktrees/src/main/resources/; pom.xml | plan-marshall |
| 6 | PLAN-07 | WS-02 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/git-store.adoc; pom.xml | plan-marshall |
| 7 | PLAN-08 | WS-02 | staged | doc/specification/general.adoc; doc/specification/git-store.adoc | plan-marshall |
| 8 | PLAN-09 | WS-02 | staged | doc/specification/general.adoc; doc/specification/git-store.adoc | plan-marshall |
| 9 | PLAN-10 | WS-02 | staged | doc/specification/general.adoc; doc/specification/git-store.adoc; pom.xml | plan-marshall |
| 10 | PLAN-11 | WS-02 | staged | doc/specification/git-store.adoc | plan-marshall |
| 11 | PLAN-12 | WS-03 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 12 | PLAN-13 | WS-03 | staged | doc/specification/general.adoc; doc/specification/git-forms.adoc; pom.xml | plan-marshall |
| 13 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc | plan-marshall |
| 14 | PLAN-15 | WS-04 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 15 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 16 | PLAN-17 | WS-05 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 17 | PLAN-18 | WS-01 | staged | doc/specification/git-testing.adoc; git-testing/pom.xml; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/ | plan-marshall |
