<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: RESTART HERE (2026-11-25, after the PLAN-22 landing analysis; the ledger is landed on main). NOTHING IS RUNNING: PLAN-13, PLAN-17 and PLAN-22 shipped (PR #64 e38e079, PR #74 729ca28, PR #76 c22700a); all three landings are analysed and reconciled, the inbox is empty and no plan is in flight. NEXT RUN 2026-11-27: PLAN-14 command EMITTED, awaiting the operator-confirmed launch (record launched via queue --transition PLAN-14 --status launched only on that word; auto_emit=false); nothing is running or launched yet. STAGED: PLAN-14 (git-forms: deadlines, typed links, running numbers; seven deliverables: d5 PLAN-23 leftovers, d6 the Audit Notary step-up switch, d7 the PLAN-13 and PLAN-22 findings incl. the major resume of a failed release and the branchMovedOn mapping) is admissible by dependency, all dependencies shipped, two free slots beyond it (parallelization_scope=3); PLAN-15 and PLAN-16 stay blocked (PLAN-14, then PLAN-15) and carry stale 'contradicted, not re-scoped' verdicts to re-ground first. HELD BACK until the operator names them: PLAN-24 (git-core/git-testing: PLAN-20 leftovers plus d3, the lost-lease classification), PLAN-25 (git-store: d1 the pinned release on the tag-only recovery path, d2 the PLAN-22 leftovers), PLAN-26 (git-timestamp: the missing tests, code defects, TLS trust statement), PLAN-27 (git-forms stages within a document, from audit-notary-002, sequential after PLAN-14, the owner decides full concept or minimal alternative at its outline). STANDING ROUTING RULE (operator, 2026-11-24, epic.md Decisions): a finding of a landing analysis goes without asking into the staged plan that fits by content (a new deliverable, same edit as its Expected Surface, claims via corpus set-verdict; a launched or running plan is frozen); only when none fits or the plan has more than 10 deliverables, into a per-module container plan (queue --add-row, held back); ask only at a real fork. SECOND STANDING RULE: every lesson, candidate lesson and tooling finding about plan-marshall (plan-marshall:* and every pm-* bundle) is filed into the lessons-routing inbox of /home/oliver/git/plan-marshall (run inbox write from that repository, sender libraries-initial-implementation, next message number -115) and retired locally after the write succeeded; 114 messages so far (lessons-moved.md); the rules of this repository's own modules are promoted into the local lessons store; both apply at every drain, landing analysis and lessons sweep. FIRST OPEN ITEMS, the operator's decisions: (1) whether and when to emit PLAN-14 (and whether to start the held-back container plans, PLAN-25 and PLAN-26 first by severity); (2) the manual dismissal of the Sonar INFO java:S2143 (AaIN1JmnF1H5L5GKrffy) of PLAN-17 on the SonarCloud page, and the three S4449 suppressions in StoreConfiguration (289, 309, 344) of PLAN-22 that carry no operator-selected record; (3) the shared-documents collision rule (Watch; PLAN-20 rebase recurrence); (4) PLAN-15 and PLAN-16 verdicts to re-ground before emit; (5) the 0.1.0 release question (main holds git-core, git-testing, git-store, git-forms and git-timestamp); (6) PLAN-14 d6 weakens the default reading of step-up (a deployment may switch it off, default stays true) and follows the consumer's owner decision, whose ADR-0005 was not read. Standing rules: scripts run from the repo root and exit 0 on error so read status: (manage-logging decision prints nothing on success: read the log, never probe it); nothing launched, landed or emitted without the operator's word; PR bodies end with the Claude Code line and the session URL. Open: stale .plan/project-architecture and empty skills_by_profile of git-store/git-forms/git-timestamp, incomplete landings PLAN-01..08,10..13,17..23. ON RESTART: run status (expect no running plan, an empty inbox, no pending land); ask the operator what to emit; when a plan lands: analyze (inbox drain, one read-only level-3 leaf per landing, re-read every checked point, apply both standing rules), then land.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-14 (WS-03)
2. PLAN-15 (WS-04)
3. PLAN-16 (WS-04)
4. PLAN-24 (WS-01)
5. PLAN-25 (WS-02)
6. PLAN-26 (WS-05)
7. PLAN-27 (WS-03)
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
- PLAN-13 (WS-03) — plan=plan-13-forms-output-workflow-stepup — PR 64 — landing=landings/PLAN-13.md — status: shipped
- PLAN-17 (WS-05) — plan=plan-17-timestamp-rfc3161 — PR 74 — landing=landings/PLAN-17.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped
- PLAN-20 (WS-01) — plan=plan-20-leftover-fixes-2 — PR 69 — landing=landings/PLAN-20.md — status: shipped
- PLAN-21 (WS-01) — plan=plan-21-merge-worktrees-into-core — PR 46 — landing=landings/PLAN-21.md — status: shipped
- PLAN-22 (WS-02) — plan=plan-22-store-hardening — PR 76 — landing=landings/PLAN-22.md — status: shipped
- PLAN-23 (WS-03) — plan=plan-23-forms-hardening — PR 59 — landing=landings/PLAN-23.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/identity-and-signing.adoc; doc/concepts/module-structure.adoc; doc/concepts/schema-driven-documents.adoc; doc/specification/git-forms.adoc; git-forms/ | plan-marshall |
| 2 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 3 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 4 | PLAN-24 | WS-01 | staged | AGENTS.md; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-testing.adoc; git-core/doc/LogMessages.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/ | plan-marshall |
| 5 | PLAN-25 | WS-02 | staged | AGENTS.md; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store/doc/LogMessages.adoc; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/test/java/de/cuioss/git/document/store/ | plan-marshall |
| 6 | PLAN-26 | WS-05 | staged | AGENTS.md; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; git-timestamp/; git-timestamp/doc/LogMessages.adoc | plan-marshall |
| 7 | PLAN-27 | WS-03 | staged | AGENTS.md; doc/concepts/module-structure.adoc; doc/concepts/schema-driven-documents.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; git-forms/; git-forms/doc/LogMessages.adoc | plan-marshall |
