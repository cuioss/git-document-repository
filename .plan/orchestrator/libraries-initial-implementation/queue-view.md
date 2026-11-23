<!-- GENERATED FILE — never hand-edit. Rendered from this epic's ledger (status.json, resume_anchor.md, queue/*.json) by `orchestrator regenerate-view --slug libraries-initial-implementation`. On a merge conflict in this file, do not merge it by hand: merge the source files, run `orchestrator regenerate-view --slug libraries-initial-implementation`, and `git add` the result. -->

# Queue view: Initial implementation of the libraries

## START HERE

**Resume anchor**: RESTART HERE (2026-11-23). State: PLAN-10 (git-store signing/trailers/verification, PR #58 open) is RUNNING. PLAN-23 (git-forms hardening) LANDED as PR #59 (bdbb5e4) but its analyze is HALF DONE. Finish it: the inbox holds ONE message, plan-23-forms-hardening-015.md (the landing). Its 14 lesson messages 001-014 are already dispositioned, archived and logged: DO NOT file those lessons again (new lessons 2026-11-23-12-001..006, five recurrences extended, 002 folded into the architecture Watch, 008 into PLAN-22 item (i), 006 discarded). To do for 015, in order: (1) inbox landing-check (complete:false, missing key effort); runbook_lane review-gate --pr-number 59 gave 0 unresolved threads, 1 review body finding, handled 0 (the landing wrote unknown); sonar read_open_issues --pr 59 is undecidable (analysis revision 43dad67 = the head the landing names), landing says sonar_open_new_issues=0; (2) dispatch ONE read-only execution-context-level-3 leaf (orchestrator.analyze, level-3; standards/three-layer-analysis.md) on merge commit bdbb5e4 (parent 36e0a9e, 29 files +2596/-317, git-forms only plus four docs) with the PLAN-23 spec body (corpus read --plan PLAN-23) and these landing claims to test: depth limit 128, number bounds in reader AND encode, validator preparation cache (thread-safe? bounded?), kinds hardening, NativeImageMetadataTest, format stays annotation, YAML 1.1 words stay plain, dependency alternatives recorded without reasons, dropped items (layout depth check, embedded-id constraint lookup), known open limit SchemaChecks.holdsRequired recursion under oneOf/anyOf/not/if/then/else/contains and the git-store Codec Javadoc; re-read every checked point yourself; (3) write landings/PLAN-23.md (template landing-analysis.md; metrics: 17,078,941 tokens, 22,989 s wall, effort unknown, model claude-sonnet-5-5; 10 deliverables, 14 commits, CodeRabbit participated, Sourcery refused on size, merge under an operator barrier-ask-override at head 43dad67; 4 Sonar issues fixed inline in db68f9a; commit 8ea86a1 carries an Opus co-author trailer); (4) corpus set-verdict PLAN-23 claims 0-2 (re-grounded at bdbb5e4); (5) queue --transition PLAN-23 shipped, then --set-row pr 59, landing landings/PLAN-23.md, plan_marshall_plan_id plan-23-forms-hardening; (6) epic.md: PLAN-23 queue annotation (shipped), Decisions entry, incomplete-landings Open Defect (PLAN-23 missing effort, review numbers unknown), release Watch (git-forms majors closed if the leaf confirms), Open Defect for PLAN-12's majors closed or reopened by the leaf; (7) one decision-log line for 015, then inbox archive; (8) route any layer-3 finding to the operator at once (a staged fix plan only on the operator's word); (9) next: PLAN-13 needs PLAN-10 (running) so nothing is emittable until PLAN-10 lands, unless the operator names another; PLAN-11 and PLAN-17 follow PLAN-10; PLAN-20 and PLAN-22 are held back. Standing rules: scripts run from the repo root, exit 0 on error so read status:; lessons with --allow-foreign-store on add only; nothing launched or landed without the operator's word; attribution session URL in PR bodies. Open: 0.1.0 release question, stale .plan/project-architecture, incomplete landings PLAN-01..08,12,18,19,21,23.
**Phase**: orchestrating
**Running**:
- PLAN-10 (WS-02)
- PLAN-23 (WS-03)
**Queue** (staged, in order):
1. PLAN-11 (WS-02)
2. PLAN-13 (WS-03)
3. PLAN-14 (WS-03)
4. PLAN-15 (WS-04)
5. PLAN-16 (WS-04)
6. PLAN-17 (WS-05)
7. PLAN-20 (WS-01)
8. PLAN-22 (WS-02)
- PLAN-01 (WS-01) — plan=git-testing-fixtures — PR 23 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-01) — plan=git-core-import — PR 25 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-01) — plan=plan-03-git-worktrees-import — PR 30 — landing=landings/PLAN-03.md — status: shipped
- PLAN-04 (WS-01) — plan=git-core-incore-write-cas — PR 35 — landing=landings/PLAN-04.md — status: shipped
- PLAN-05 (WS-01) — plan=plan-05-git-core-merge-read-notes — PR 37 — landing=landings/PLAN-05.md — status: shipped
- PLAN-06 (WS-01) — plan=implement-plan-06-build-guards — PR 39 — landing=landings/PLAN-06.md — status: shipped
- PLAN-07 (WS-02) — plan=plan-07-store-module-registry-spi — PR 41 — landing=landings/PLAN-07.md — status: shipped
- PLAN-08 (WS-02) — plan=plan-08-store-drafts-save — PR 48 — landing=landings/PLAN-08.md — status: shipped
- PLAN-09 (WS-02) — plan=plan-09-store-release-index-listeners — PR 52 — landing=landings/PLAN-09.md — status: shipped
- PLAN-12 (WS-03) — plan=plan-12-forms-kinds-schema-layout — PR 54 — landing=landings/PLAN-12.md — status: shipped
- PLAN-18 (WS-01) — plan=plan-18-leftover-fixes — PR 28 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-01) — plan=plan-19-git-worktrees-hardening — PR 31 — landing=landings/PLAN-19.md — status: shipped
- PLAN-21 (WS-01) — plan=plan-21-merge-worktrees-into-core — PR 46 — landing=landings/PLAN-21.md — status: shipped

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Lane |
|---|------|------------|--------|--------------------|------|
| 1 | PLAN-10 | WS-02 | running | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store; git-store/; pom.xml | plan-marshall |
| 2 | PLAN-11 | WS-02 | staged | AGENTS.md; doc/README.adoc; doc/specification/git-store.adoc; git-store; git-store/ | plan-marshall |
| 3 | PLAN-13 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; git-forms; git-forms/; pom.xml | plan-marshall |
| 4 | PLAN-14 | WS-03 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-forms.adoc; git-forms/ | plan-marshall |
| 5 | PLAN-15 | WS-04 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc; pom.xml | plan-marshall |
| 6 | PLAN-16 | WS-04 | staged | AGENTS.md; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/git-directory.adoc | plan-marshall |
| 7 | PLAN-17 | WS-05 | staged | AGENTS.md; README.adoc; doc/README.adoc; doc/concepts/module-structure.adoc; doc/specification/general.adoc; doc/specification/git-timestamp.adoc; pom.xml | plan-marshall |
| 8 | PLAN-20 | WS-01 | staged | AGENTS.md; README.adoc; doc/clients/pm-mcp-core.adoc; doc/specification/general.adoc; doc/specification/git-core.adoc; doc/specification/git-testing.adoc; git-core/doc/LogMessages.adoc; git-core/pom.xml; git-core/src/main/java/de/cuioss/git/document/core/; git-core/src/main/java/de/cuioss/git/document/core/worktrees/; git-core/src/main/resources/META-INF/native-image/; git-core/src/test/java/de/cuioss/git/document/core/; git-core/src/test/java/de/cuioss/git/document/core/worktrees/; git-testing/src/main/java/de/cuioss/git/document/testing/; git-testing/src/test/java/de/cuioss/git/document/testing/; pom.xml | plan-marshall |
| 9 | PLAN-22 | WS-02 | staged | AGENTS.md; doc/specification/general.adoc; doc/specification/git-store.adoc; git-store/doc/LogMessages.adoc; git-store/pom.xml; git-store/src/main/java/de/cuioss/git/document/store/; git-store/src/main/java/module-info.java; git-store/src/test/java/de/cuioss/git/document/store/ | plan-marshall |
| 10 | PLAN-23 | WS-03 | running | AGENTS.md; doc/README.adoc; doc/specification/general.adoc; doc/specification/git-forms.adoc; git-forms/doc/LogMessages.adoc; git-forms/pom.xml; git-forms/src/main/java/de/cuioss/git/document/forms/; git-forms/src/main/java/module-info.java; git-forms/src/main/resources/META-INF/native-image/; git-forms/src/test/java/de/cuioss/git/document/forms/ | plan-marshall |
