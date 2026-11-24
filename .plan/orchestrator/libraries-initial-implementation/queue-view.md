<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: RESTART HERE (2026-11-24, after PLAN-11/PLAN-20 analysis and land of PR #70). RUNNING (operator-confirmed): PLAN-13 (forms output/workflow/step-up, PR #64 open), PLAN-17 (git-timestamp, RFC 3161) and PLAN-22 (git-store hardening, four deliverables); parallelization_scope=3 is full. Blocked: PLAN-14 (needs PLAN-13; now five deliverables), PLAN-15 (PLAN-14), PLAN-16 (PLAN-15). Held back until the operator names it: PLAN-24 (git-core and git-testing hardening, the container plan for those modules, staged 2026-11-24). STANDING ROUTING RULE (operator, 2026-11-24, in epic.md Decisions): a finding of a landing analysis goes without asking into the staged plan that fits by content (as a new deliverable, same edit as its Expected Surface, claims through corpus set-verdict; a launched or running plan is frozen); only when none fits or the fitting plan has more than 10 deliverables, into a per-module container plan (queue --add-row, held back); ask only at a real fork. Routed so far: PLAN-10 and PLAN-11 findings to PLAN-22 d4, PLAN-23 findings to PLAN-14 d5, PLAN-20 findings to PLAN-24. UNLANDED LEDGER CHANGES (not yet on main; the next 'land' carries them): PLAN-17/PLAN-22 running, PLAN-14 deliverable 5, PLAN-24 staged, the routing rule. FIRST OPEN ITEMS, the operator's decisions: (1) the shared-documents collision rule (Watch; PLAN-20 rebase recurrence); (2) PLAN-15 and PLAN-16 carry stale 'contradicted, not re-scoped' verdicts (PLAN-16's is the SaveStep gap PLAN-09 shipped): re-ground them before they are emitted; (3) the 0.1.0 release question. Standing rules: scripts run from the repo root and exit 0 on error so read status: (manage-logging decision prints nothing on success: read the log, never probe it); lessons with --allow-foreign-store on add only; nothing launched, landed or emitted without the operator's word; PR bodies end with the Claude Code line and the session URL. Open: stale .plan/project-architecture and empty skills_by_profile of git-store/git-forms, incomplete landings PLAN-01..08,10,11,12,18,19,20,21,23. SECOND STANDING RULE (operator, 2026-11-24, epic.md Decisions): every lesson, candidate lesson and tooling finding about plan-marshall (components plan-marshall:* and any pm-* domain bundle) is filed into the lessons-routing inbox of /home/oliver/git/plan-marshall (run inbox write from that repository, sender libraries-initial-implementation, next message number -080) and retired locally after the write succeeded; 76 lessons and one finding already moved (lessons-moved.md); this applies at every drain, landing analysis and lessons sweep. Unlanded ledger changes now also include lessons-moved.md and both rules. The change request that makes the orchestrator do this itself is filed (priority high, message libraries-initial-implementation-078). A second change request (priority high, message libraries-initial-implementation-079) asks the orchestrator to apply the routing rule itself.
**Phase**: orchestrating
**Running**:
- PLAN-13 (WS-03) — plan=plan-13-forms-output-workflow-stepup
- PLAN-17 (WS-05) — plan=plan-17-timestamp-rfc3161
- PLAN-22 (WS-02) — plan=plan-22-store-hardening
**Queue** (staged, in order):
1. PLAN-14 (WS-03)
2. PLAN-15 (WS-04)
3. PLAN-16 (WS-04)
4. PLAN-24 (WS-01)
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
| 5 | PLAN-17 | WS-05 | running | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 6 | PLAN-22 | WS-02 | running | AGENTS.md; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store/doc/LogMessages.adoc; git-store/pom.xml; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/main/java/module-info.java; git-store/src/test/java/de/cuioss/git/document/store/ | plan-marshall |
| 7 | PLAN-24 | WS-01 | staged | AGENTS.md; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-testing.adoc; git-core/doc/LogMessages.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/ | plan-marshall |
