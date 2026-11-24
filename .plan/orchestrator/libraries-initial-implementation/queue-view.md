<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: RESTART HERE (2026-11-24, PLAN-11 and PLAN-20 analysed). PLAN-11 SHIPPED (PR #66, b30ec14) and PLAN-20 SHIPPED (PR #69, f7eda4e): landings/PLAN-11.md and landings/PLAN-20.md written, rows stamped, 33 inbox messages dispositioned and archived (12 new lessons 2026-11-24-07-001..012, 12 recurrences appended, 2026-11-23-14-011 repaired). RUNNING: PLAN-13 (PR #64 open). EMITTED, NOT LAUNCHED (auto_emit=false; record launched only when the operator confirms, never running unseen): PLAN-17 (git-timestamp, opens with the RFC 3161 library decision) and PLAN-22 (git-store hardening, now four deliverables: deliverable 4 folds the PLAN-10 ten minors and PLAN-11 one major plus eleven minors on the operator's word); accepted overlaps: PLAN-17/PLAN-13 4-5 files, PLAN-22/PLAN-13 2 files, PLAN-17/PLAN-22 2 files (AGENTS.md, general.adoc). Blocked: PLAN-14 (needs PLAN-13), PLAN-15 (PLAN-14), PLAN-16 (PLAN-15). FIRST OPEN ITEMS, the operator's decisions: (1) where PLAN-20's layer-3 findings in git-core go (Epic Open Defect 'PLAN-20 defects in git-core and git-testing' NOT YET ROUTED: one major, the strict path check refuses a Delete of a Windows-invalid name already in a tree, plus four minor); (2) where PLAN-23's layer-3 findings in git-forms go (one MAJOR DataNode pointer-string amplification, five minor; NOT YET ROUTED; candidates a git-forms hardening plan after PLAN-13, or into PLAN-13); (3) the shared-documents collision rule (Watch, now with the PLAN-20 rebase recurrence); (4) PLAN-15 and PLAN-16 carry stale 'contradicted, not re-scoped' verdicts (PLAN-16's is the SaveStep gap PLAN-09 shipped): re-ground them before they are emitted. Stage nothing without the operator's word. Standing rules: scripts run from the repo root and exit 0 on error so read status: (manage-logging decision prints nothing on success: read the log, never probe it); lessons with --allow-foreign-store on add only; nothing launched, landed or staged as a fix plan without the operator's word; PR bodies end with the Claude Code line and the session URL. Open: 0.1.0 release question (git-store anchoring and git-forms are on main with open majors/minors), stale .plan/project-architecture and empty skills_by_profile of git-store/git-forms, incomplete landings PLAN-01..08,10,11,12,18,19,20,21,23.
**Phase**: orchestrating
**Running**:
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
- PLAN-11 (WS-02) — plan=plan-11-store-anchoring-signed — PR 66 — landing=landings/PLAN-11.md — status: shipped
- PLAN-12 (WS-03) — plan=plan-12-forms-kinds-schema-layout — PR 54 — landing=landings/PLAN-12.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped
- PLAN-20 (WS-01) — plan=plan-20-leftover-fixes-2 — PR 69 — landing=landings/PLAN-20.md — status: shipped
- PLAN-21 (WS-01) — plan=plan-21-merge-worktrees-into-core — PR 46 — landing=landings/PLAN-21.md — status: shipped
- PLAN-23 (WS-03) — plan=plan-23-forms-hardening — PR 59 — landing=landings/PLAN-23.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-13 | WS-03 | running | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; git-forms; git-forms/; pom.xml | plan-marshall |
| 2 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc; git-forms/ | plan-marshall |
| 3 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 4 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 5 | PLAN-17 | WS-05 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 6 | PLAN-22 | WS-02 | staged | AGENTS.md; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store/doc/LogMessages.adoc; git-store/pom.xml; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/main/java/module-info.java; git-store/src/test/java/de/cuioss/git/document/store/ | plan-marshall |
