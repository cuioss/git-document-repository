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
- PLAN-03 / PLAN-04 — Started side by side (git-worktrees vs git-core). Since the surface correction of 2026-11-18 they
  share `AGENTS.md` and `doc/README.adoc` (two files), so the gate put the pair to the operator, who accepted it; the real
  collision is the "Current state" paragraph and the "Implemented are ..." sentence. PLAN-03 shipped (PR #30), PLAN-04
  shipped (PR #35, `c8feb0b`).
- PLAN-04 / PLAN-05 — Each carries a deliverable 5 folded from the landing analysis of PLAN-02 (PLAN-04: code-side
  hardening of the taken-over operations; PLAN-05: test and verification residuals). Five deliverables each, below the
  split guard; they proceed unsplit because every item edits the files the plan already edits.
- PLAN-19 — Hardening of `git-worktrees` from the landing analysis of PLAN-03 (staged 2026-11-18). Depends on PLAN-03 only;
  shares `doc/specification/general.adoc` (and `GitOutcome.java` if a new outcome is needed) with PLAN-04, accepted by the
  operator. Not a prerequisite of PLAN-06 or later plans. Shipped (PR #31, `e2aced9`): the diff shares only `general.adoc`
  with PLAN-04, `GitOutcome.java` was not touched (the refused branch name reuses `REFUSED_BY_POLICY`).
- PLAN-06 — Last foundation plan. Shipped (PR #39, `e43c7e9`, `landings/PLAN-06.md`): it ran alone after PLAN-05; the consumer check
  became an Enforcer execution inside `git-core` (the owner's decision) and `git-worktrees` ships no native-image metadata. After
  it: the operator runs `/release` for 0.1.0 (not a queue row, see Decisions), then the aspect modules start (PLAN-07 first).
- PLAN-07..PLAN-11 — Strictly sequential: all edit `git-store/`, which the surface reader cannot see until PLAN-07
  creates it. The same holds for PLAN-12..14 (`git-forms/`), PLAN-15..16 (`git-directory/`) and PLAN-17
  (`git-timestamp/`).
- PLAN-12 — Can start once PLAN-08 has landed, in parallel with PLAN-09..11 (multi-file overlap on `pom.xml`,
  `general.adoc`, `AGENTS.md` → the gate puts it to the operator). PLAN-08 has landed; on 2026-11-21 the operator confirmed
  PLAN-09 and PLAN-12 together and the overlap it names (`general.adoc`, `AGENTS.md`, `doc/README.adoc`; `pom.xml` is only in
  PLAN-12's surface, `git-store/pom.xml` lies inside PLAN-09's `git-store/`). PLAN-12 shipped (PR #54, `8d09629`, `landings/PLAN-12.md`): the
  `git-forms` module (53 paths, +8903/-41), the largest change of the epic, merged on top of PLAN-09 after an operator override of the
  review barrier (CodeRabbit paused before the last three commits, the optional reviewer refused the diff on size). The landing does not
  say whether the rebase onto PLAN-09's documents conflicted, so the pairing's collision outcome is not recorded. Its two major defects
  (no nesting-depth limit, an unbounded decimal exponent) and its minors went to PLAN-23.
- PLAN-23 — `git-forms` hardening (staged 2026-11-22 on the operator's word, WS-03): both majors of PLAN-12 first, then the minors
  and the missing tests. Shipped (PR #59, `bdbb5e4`, `landings/PLAN-23.md`, 29 paths, +2596/-317): the depth limit 128 and the number bounds
  (1000 digits, exponent 1000) in the reader and in `encode`, the kinds hardening, the once-per-schema validator preparation and a rewritten
  native-image test; it ran beside PLAN-10 (three-file overlap, the operator accepted two) and merged under an operator override of the review
  barrier (Sourcery refused on size, CodeRabbit participated). Its own analysis found one major (the JSON Pointer string amplification of
  `DataNode`, an `OutOfMemoryError` from one small document, mechanism confirmed, size not shown) and five minor defects; routing is the
  operator's decision and is pending. PLAN-13 depends on it (WS-03: PLAN-12, PLAN-23, PLAN-13) and now waits only for PLAN-10.
- PLAN-08 — Shipped (PR #48, `c1e02fc`, `landings/PLAN-08.md`): the write path of `git-store` (41 paths, +7661/-54): drafts, explicit save
  with lease, conflicts, `expectedBlobId`, draft expiry with garbage collection, the bound of `save.max-attempts` (100) and the
  `commit.email` setting. It ran alone after PLAN-21. The spec said five deliverables, the lane reported seven (the outline split them);
  fix tasks of two loop-backs edited `git-core` against the outline (`FetchInput.prune`, the public `MIRROR_FETCH_REF_SPECS`). The
  layer-3 minors went to PLAN-20 (deliverable 8); the missing per-attempt save step went to PLAN-09 (deliverable 5).
- PLAN-09 — Shipped (PR #52, `4a5eed5`, `landings/PLAN-09.md`): release with tag and repair, the in-memory index, post-commit listeners
  and the per-attempt save step (37 paths, +6357/-164), emitted and run beside PLAN-12. It gained its fifth deliverable (the save step,
  GDR-STORE-3 amended) from PLAN-08's analysis on 2026-11-21. The run added a test-scope dependency (JOL 0.17, owner-approved) and
  `TreeEdit.Mode` to `git-core` against the spec's read-only line (owner-approved after the fact, the second plan to cross it). The first
  landing of the epic that `landing-check` reports complete. Its minor defects went to PLAN-22.
- PLAN-22 — `git-store` hardening (staged 2026-11-22 on the operator's word): PLAN-20's former deliverables 7 (PLAN-07's leftovers) and
  8 (PLAN-08's), plus PLAN-09's 13 items. **Held back from `next` until the operator releases it**; queued after PLAN-11 and strictly
  sequential with PLAN-10 and PLAN-11 on `git-store/` (they edit `ExplicitSave` and `DocumentRelease` too). Later analyses fold further
  `git-store` leftovers into it.
- PLAN-10, PLAN-12, PLAN-13, PLAN-17 — Each opens with an owner decision on a third-party library; expect a question
  early in the plan's refine phase.
- PLAN-18 — Shipped (PR #28). It was the collection plan for the minor leftovers of PLAN-01; once started its spec is
  frozen, so the leftovers of later landings fold into the staged specs they touch (see Decisions) or into a Watch.
- PLAN-05 — Shipped (PR #37, `14269fe`, `landings/PLAN-05.md`). It ran alone after PLAN-04, so no rebase conflict in the state
  documents; its outline split the five spec deliverables into six after the operator redesigned two recommendations.
- PLAN-04 — Shipped (PR #35, `c8feb0b`, `landings/PLAN-04.md`). PLAN-04 landed after PLAN-19 and its rebase conflicted by hand
  in four documents (`doc/README.adoc`, `AGENTS.md`, `README.adoc`, `doc/specification/general.adoc`): the pairing gate had
  predicted two of them plus `general.adoc`; the root `README.adoc` was in neither spec's surface. Sibling plans keep editing the
  same state lists, so a plan that changes a module's state expects those hand merges.
- PLAN-07 — Shipped (PR #41, `9bdc5ba`, `landings/PLAN-07.md`), the first aspect-module plan: it created `git-store` (41 paths, +5091/-58).
  It ran alone, started on the operator's word after the caution that the modules are released together, so `git-store` is now on
  `main` and reaches the 0.1.0 release unless that is cut first or leaves it out. The operator merged #42 (the AGENTS.md merge
  authorisation) and #43 (a comment-only change in `StoreConfiguration.java`) after it. The spec said four deliverables, the lane
  reported six (the outline split them, fix tasks followed).
- PLAN-21 — Shipped (PR #46, `9e3cfb0`, `landings/PLAN-21.md`): `git-worktrees` is merged into `git-core` as the package
  `de.cuioss.git.document.core.worktrees` (33 paths, +660/-935), the reactor has three modules (`git-core`, `git-store`,
  `git-testing`). It ran alone after PLAN-07, emitted alone because it shared three files with PLAN-08. The owner decisions put at its
  outline differ from the spec's defaults: the requirement ids were renumbered (`GDR-WT-1..4` became `GDR-CORE-12..15`), the log ids
  became INFO 2-4 and WARN 102-104 (the worktrees refusal stays the separate record `WORKTREE_CAPABILITY_REFUSED`), and ADR-0004 was
  rewritten in place with no new ADR. The names `GDR-WT-n` and `GIT_WORKTREES` in older landing reports and decisions are superseded.
- PLAN-20 — Second collection plan (staged 2026-11-19 on the operator's word): the layer-3 defects of PLAN-19 and PLAN-04, the
  test gaps beside them and the `git-testing` leftovers of PLAN-18, in one plan. **Held back from `next` until the operator
  releases it**; later analyses fold further leftovers into it, in the same edit as its Expected Surface. PLAN-05, with
  which it shared four declared entries, has shipped; it now shares `AGENTS.md` and `general.adoc` with PLAN-06 and the later
  plans that edit them, so the gate puts the pair to the operator when both are launched together. PLAN-05's analysis folded
  `InCoreMerger`, `GitNotes`, `JGitRepositoryOperations.log`, the guarded batch and the EasyMock question into it; all ten
  PLAN-04 defects are still present at `14269fe`. PLAN-06's analysis added deliverable 6 (the build guards' residue) and the
  root `pom.xml`, `git-core/pom.xml`, the native-image directory and `README.adoc` to its surface; PLAN-20 now has six
  deliverables, at the split guard, and proceeds unsplit because the operator asked for one collection plan and each
  deliverable is independent (the operator may split it when releasing it). PLAN-07's analysis added deliverable 7 (`git-store`: a
  token in a remote's query, a throwing `id()`, eager anchor providers, the textual anchor-key check and three test residues) and the
  `git-store` entries to its surface; it now has seven deliverables and proceeds unsplit on the same ground. PLAN-21 has landed and PLAN-20
  was re-grounded in the same act (2026-11-20): its worktrees paths, the spec references (`git-core.adoc` § Working Trees), the Expected
  Surface and the Done-when base (`9e3cfb0`) moved into `git-core`, the five worktrees claims were re-stamped at `9e3cfb0`, deliverable 2
  gained item (e) (two documentation residues of PLAN-21's analysis) and item (g) of deliverable 7 collapsed (the fourth domain-term copy
  went with the module). It is still held back until the operator names it in a `next` round. PLAN-08 has landed (2026-11-21); its
  analysis added deliverable 8 (`git-store` write-path leftovers: unbounded guard maps, a permanent rejection retried up to the bound,
  `NOT_FOUND` for a malformed expected blob id, a non-commit draft reference blocking expiry, cross-process garbage collection, the
  false "no suppression" claim, a weak concurrency test, an unenforced "declared twice" check) and an observed claim; it now has eight
  deliverables and proceeds unsplit on the same ground, with a note that deliverable 8 touches `ExplicitSave` and `StoreWriter`, which
  PLAN-09 edits, so it is released after PLAN-09 or split. Its `git-store` claim stamps (26 to 30), displaced by one bullet since the
  PLAN-07 analysis, were re-stamped on the right claims at `c1e02fc`. On 2026-11-22 (PLAN-09's landing) deliverables 7 and 8 moved to the
  new PLAN-22 with their claims, and PLAN-20 has six deliverables again (at the split guard), with the flaky `publishLosesRace` as item (f)
  of deliverable 4 and no `git-store` entry left in its Expected Surface. **Shipped (2026-11-24, PR #69, `f7eda4e`, `landings/PLAN-20.md`):**
  49 files, +3663/-411, six deliverables traced; it ran beside PLAN-11 and PLAN-13 (the planned three-file overlap was two in the diff: the
  root `pom.xml` was not touched, only `git-core/pom.xml`) and was rebased onto PLAN-11 at `branch-cleanup` (content-clean, which cost a
  second finalize pass). Its own analysis found one major (`InCoreWriter.checkPaths` refuses a `Delete` of a Windows-invalid name that is
  already in a tree) and four minor defects.
- PLAN-11 — Shipped (PR #66, `b30ec14`, `landings/PLAN-11.md`, 37 files, +8591/-57): the anchoring engine, checkpoints, proofs as notes, the
  provider `signed` and the verifier; it ran beside PLAN-13 and PLAN-20 (the overlap realised is the state lists only). Its public
  `AnchorProvider.obtain` gained a `commitTime` parameter (PLAN-17 implements this SPI). Its analysis found one major (the verifier does not
  check the checkpoint chain) and eleven minor defects; routed to PLAN-22 deliverable 4 on the operator's word.
- PLAN-24 — `git-core` and `git-testing` hardening, the container plan of the epic for those modules (staged 2026-11-24 under the standing routing
  rule, WS-01): PLAN-20's one major and four minor defects and its test, fixture and document residue. **Held back from `next` until the operator
  releases it**; later `git-core` and `git-testing` leftovers fold into it until it is launched. It declares `AGENTS.md`, `general.adoc` and the
  `git-core` and `git-testing` directories, so the gate puts it to the operator against any plan in flight that declares them.
- PLAN-14 — gained deliverable 5 on 2026-11-24 (PLAN-23's `git-forms` leftovers: the pointer-string amplification of `DataNode`, the unbounded
  `holdsRequired` chain, the hexadecimal digit drift and the residue), five deliverables, below ten; it stays blocked on PLAN-13. It gained
  deliverable 6 the same day (the step-up switch `step-up.enabled` and single-factor `step-up.amr` values of GDR-FORMS-7, from the Audit Notary request
  `audit-notary-001`); six deliverables, below ten, and two concept documents join its Expected Surface (seven declared entries). It gained
  deliverable 7 on 2026-11-24 from the analysis of PLAN-13 (the resume of a failed release, on the operator's answer "resume", and the
  `git-forms` minors L3-3, L3-4, L3-6, L2-1, L2-2), seven deliverables, below ten, no new surface. **Shipped 2026-11-27 (PR #81, `0187cf3`,
  `landings/PLAN-14.md`)**: emitted and launched on the operator's word of 2026-11-27, 65 files (+5703/-422), all seven deliverables trace to code and tests; the
  one `git-store` change the spec ruled out (the accessor `StoreIndex.sourceEntries`) was lifted by the operator during review. Its analysis found two major
  layer-3 defects in the resume of a failed release, seven minors and eight test and file gaps; routing is in Decisions and Open Defects (awaiting the operator's word).
- PLAN-13 — Shipped (PR #64, `e38e079`, `landings/PLAN-13.md`): generated output, workflow and step-up per transition in `git-forms` (45 files,
  +7569/-53); four spec deliverables, six reported. The one change to `git-store` the spec ruled out (a pinned release, GDR-STORE-6) was approved by
  the operator during review. It merged under an operator-recorded authorization over a review gap (Sourcery refused on size). Its analysis found one
  major defect (a failed release cannot be retried through the transition API), five minor ones and the two spec/surface gaps; routing is in
  Decisions.
- PLAN-25 — `git-store` container plan (staged 2026-11-24 on the operator's answer, WS-02): the pinned release on the tag-only recovery path (L3-2
  of PLAN-13). **Held back from `next` until the operator releases it**; strictly sequential after PLAN-22 on `git-store/`; later `git-store`
  leftovers fold into it until it is launched. Re-grounded on 2026-11-25 at `c22700a` after PLAN-22 shipped: deliverable 1 is the pinned release on the
  tag-only recovery path (now carrying `branchMovedOn`), deliverable 2 the leftovers of PLAN-22's analysis; the former deliverable 2 (the unbounded read of a
  stored anchor proof, from PLAN-17's analysis) was refuted by PLAN-22 and dropped. Two deliverables, below ten.
- PLAN-17 — Shipped (PR #74, `729ca28`, `landings/PLAN-17.md`): the optional module `git-timestamp` with the `rfc3161` anchor provider (32 files),
  on an own bounded DER/CMS reader and writer, no dependency (the owner's decision of 2026-11-24); the reactor has four modules. The spec said four
  deliverables, the lane reported five. Merged with the open Sonar INFO java:S2143 (operator's choice) and without CodeRabbit's review of the
  last two commits (its quota ran out; operator's choice "Merge without it"). Its analysis found no blocker and no major code defect, one major
  test gap (the core verification rules have no test that can fail) and six minor defects; routing is in Decisions.
- PLAN-26 — `git-timestamp` container plan (staged 2026-11-25 under the routing rule, WS-05): the test gaps, the code defects and the TLS trust
  statement of PLAN-17's analysis, three deliverables. **Held back from `next` until the operator releases it**; later `git-timestamp`
  leftovers fold into it until it is launched.
- PLAN-27 — `git-forms` stages within a document (staged 2026-11-25 on the operator's answer, WS-03), from the Audit Notary request
  `audit-notary-002`: five deliverables (the decision, the condition language, stages in the workflow, `complete` and `reopen`, the gate).
  **Held back from `next` until the operator releases it**; strictly sequential after PLAN-14 on `git-forms/`. At its outline the owner decides
  whether the library takes the full concept or the minimal excluded-parts alternative.
- PLAN-28 — identity seams (staged 2026-11-27 on the operator's word "stage and land", WS-02): an opaque `Attestation` recorded as signed trailers in `git-store`, the
  `IdentityVerifier` / `verifyIdentity` callback and the removal of `StepUpPolicy` in `git-forms`, the person key `authority` and `subject`, a new ADR amending ADR-0005 and ADR-0006,
  the documents, and the rename of `TrailHand` to `Audit-Notary`; four deliverables, below six. **The next plan to emit, NOT before the restart of 2026-11-27** (the operator).
  PLAN-15, PLAN-16, PLAN-25 and PLAN-27 now name PLAN-28 in their Depends-on line and run after it (the shared documents and the `git-store/` and `git-forms/` trees are strictly
  sequential). It declares 17 surface entries (the two module trees, `doc/adr/`, three specifications, six concepts, `doc/README.adoc`, `AGENTS.md`); the gate puts it to the
  operator against any plan in flight that shares more than one of them. The three open points of the analysis were taken as recommended unless the operator says otherwise at the
  outline: the verifier receives the whole transition context (the request carries attestation, pseudonym, key and transition), the evidence trailer `Identity-Verification: performed`
  is in, and the optional `commit.required-trailers` is decided at the outline. Updated 2026-11-28 after the check against the local `Audit-Notary` checkout (`11169dc`): `Attestation` is an
  interface the application can implement, the `Transition` requirement stays readable, and the consumer's linked addresses stay stable (see the spec's claims).
- PLAN-29 — `git-forms` container plan for the findings of PLAN-14's landing (staged 2026-11-28 on the operator's word "stage plan-14 defects", WS-03): the two majors (the resume of a failed
  release after a narrative-only revision, the allocated key of a release-first numbered entry), the minors L3-4, L3-5, L3-7 to L3-9, the four points reading could not settle (L3-3, L3-10 to
  L3-12), the test gaps L2-1 to L2-7 and the GDR-FORMS-6 wording; four deliverables, below six. **Held back from `next` until the operator releases it**; runs after PLAN-28 and before
  PLAN-27 (PLAN-27's Depends-on names it); declares `git-forms/` and `git-forms.adoc` (and, as hypotheses, `general.adoc` and `AGENTS.md`). Later `git-forms` leftovers fold into it until it is launched.
- PLAN-17 — Emitted on 2026-11-24 beside PLAN-13 (PLAN-11 shipped, so its dependency is met); the operator accepted the 4 to 5 shared files with
  PLAN-13 (`AGENTS.md`, `doc/README.adoc`, `general.adoc`, `pom.xml`, `module-structure.adoc` against the live plan) and the two shared with
  PLAN-22 (`AGENTS.md`, `general.adoc`). Opens with the owner decision on the RFC 3161 library.
- PLAN-22 — Released and emitted on 2026-11-24 on the operator's word, after PLAN-10's and PLAN-11's `git-store` findings were folded into it as
  deliverable 4 (no new surface); it has four deliverables and proceeds unsplit. Shipped (PR #76, `c22700a`, `landings/PLAN-22.md`, 2026-11-25): the
  leftovers of PLAN-07 to PLAN-11 in `git-store`, widened by operator decisions into `git-core` (the lost-lease classification) and the public
  `git-store` API (`StoreWriter.open` with `collections`, `Release.branchMovedOn`, `SaveStep.Attempt.mode`, `IdentityClaims.of`); seven CodeRabbit rounds,
  the last head never reviewed by it (it paused itself). Its analysis found two layer-3 majors (the tag-only recovery ignores the pin, and `git-forms`
  ignores `branchMovedOn`), one more that is an accepted limit (the lease classification by exact text), ten minor ones and three layer-2 gaps. It
  refuted PLAN-25's former deliverable 2 (the unbounded note read is bounded now); routing is in Decisions.

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
- 2026-11-18 — `orchestrator.use_worktree` switched on at the operator's instruction (ledger edits had been written to
  `main` by mistake and were reverted there); ledger state now reaches `main` only through `land`. The knob lives in
  `.plan/marshal.json` and landed through PR #22.
- 2026-11-18 — PLAN-01 shipped (PR #23, `6cc64f0`); landing report `landings/PLAN-01.md`. Its three-layer analysis found
  no blocker or major issue. Its verify-first clause was contradicted (the parent chain already manages the test stack, so
  only JGit went into the root `dependencyManagement`); the spec was not rewritten, the verdict is stamped on it.
  PLAN-01 declared 7 paths and touched 31 (seven documentation files, from two Q-Gate findings); no staged spec needed a
  declaration correction, because PLAN-02..PLAN-17 already declare those documents.
- 2026-11-18 — Staged PLAN-18 (`leftover-fixes`, WS-01) as a general collection plan for the minor leftovers of landed
  plans, starting with the layer-2 and layer-3 findings of PLAN-01 and the EasyMock deviation of `git-testing`.
  Operator's instruction ("a general fix plan as a collection for this and other leftovers"). Alternatives offered: a
  Watch only; ignore. It is held back from `next` until the operator releases it, so `analyze` can keep folding
  leftovers into it.
- 2026-11-18 — Candidate lessons of PLAN-01 (16 inbox messages). Operator's choice: the eight plan-marshall tooling
  lessons are filed in the local lessons store with `--allow-foreign-store` (`2026-11-18-07-001` to `-007`; the two
  outline lessons 001 and 002 merged into one entry); the eight lessons about this repository's own code are discarded
  as shipped, because their rules are embodied in the merged code, tests and Javadoc and later plans use the
  `git-testing` fixtures rather than writing their own.

> ↪ Relocated to `settled.md` § "Decisions 2026-11-18 — PLAN-02, PLAN-18, PLAN-03, PLAN-04 and PLAN-19: started, analysed, routed" — PLAN-02, PLAN-18, PLAN-03 and PLAN-19 shipped and analysed, their lessons disposed of and their findings routed (PLAN-04's rule below stays live)
> ↪ Relocated to `settled.md` § "Decisions 2026-11-19 — PLAN-19, PLAN-04 and PLAN-05: landings, lessons and routing" — PLAN-19, PLAN-04 and PLAN-05 shipped and analysed, their lessons disposed of, their defects routed into PLAN-20
> ↪ Relocated to `settled.md` § "Decisions 2026-11-20 — PLAN-06 shipped, the worktrees merge decision, PLAN-21 staged" — PLAN-06 shipped and analysed, and the `git-worktrees` merge into `git-core` decided and shipped as PLAN-21
> ↪ Relocated to `settled.md` § "Decisions 2026-11-20 to 2026-11-21 — PLAN-07, PLAN-21 and PLAN-08: started, landed, analysed, routed" — PLAN-07, PLAN-21 and PLAN-08 shipped and analysed, their lessons disposed of and their findings routed (PLAN-20, PLAN-22)
> ↪ Relocated to `settled.md` § "Decisions 2026-11-22 to 2026-11-23 — PLAN-09, PLAN-12, PLAN-23 and PLAN-10: landed, analysed, routed" — PLAN-09, PLAN-12 and PLAN-23 shipped and analysed, their lessons disposed of and their findings routed (PLAN-22, PLAN-23, PLAN-14)
> ↪ Relocated to `settled.md` § "Decisions 2026-11-23 to 2026-11-24 — PLAN-20, PLAN-10, PLAN-11 and PLAN-20 landed: analysed, dispositioned, routed" — PLAN-10, PLAN-11 and PLAN-20 shipped and analysed, the 33-message drain dispositioned and the emit round of 2026-11-24 closed
- 2026-11-24 — **STANDING ROUTING RULE (the operator's, binding for every later `analyze`, `handle-open` and `lessons` pass of this epic).** A
  defect, test gap, documentation gap or leftover that a landing analysis finds (layer 1 to 3) is routed without asking, in this order:
  (1) into the **staged plan that fits by content** (the same module or the same surface, not yet launched; a launched or running plan's
  spec is frozen and never takes a fold), as a new deliverable of that plan, in the same edit as its Expected Surface and with the claims
  stamped through `corpus set-verdict` (the same-act rule of the Fold disposition); (2) only when no staged plan fits, or the fitting plan
  already holds **more than 10 deliverables**, into a **follow-up container plan for the corresponding module or modules**: one container per
  module (or module group), staged with `queue --add-row`, held back from `next` until the operator releases it, and the later findings of
  that module fold into it until it is launched. The orchestrator records the routing as an Open Defect entry that names the plan and the
  deliverable, and asks the operator only where the rule leaves a real fork (a fix that changes a documented behaviour, an owner decision, a
  finding that touches two modules). The rule replaces the former "NOT YET ROUTED, to be placed on the operator's word" step; staging a fix
  plan for a finding still never launches or emits it. The scope-bloat guard (about six deliverables) is not changed by this rule for a plan
  that is emitted: a folded plan above six records its proceed-or-split rationale as a decision. *Applied on 2026-11-24:* PLAN-10's and
  PLAN-11's `git-store` findings went into PLAN-22 (deliverable 4, four deliverables), PLAN-23's `git-forms` findings into PLAN-14
  (deliverable 5, five deliverables), PLAN-20's `git-core` and `git-testing` findings into the new container plan PLAN-24 (no staged plan edits
  those modules).
- 2026-11-24 — PLAN-17 and PLAN-22 confirmed running on the operator's word ("both are running"; both plan directories observed, `plan_marshall_plan_id`
  stamped). PLAN-13, PLAN-17 and PLAN-22 are in flight, so `parallelization_scope=3` is full.
- 2026-11-24 — PLAN-24 (`git-core` and `git-testing` hardening, WS-01, two deliverables) staged under the routing rule, held back from `next`;
  PLAN-14 gained deliverable 5 (the PLAN-23 findings L3-1 to L3-6 and L2-1 to L2-4), adding no file surface; its claims are stamped
  (PLAN-24 claims 0 to 3 `corroborated` at `f7eda4e`).
> ↪ Relocated to `settled.md` § "Decisions 2026-11-24 — the Audit Notary step-up request folded into PLAN-14, and the plan-marshall lessons moved" — PLAN-14 shipped the fold (PR #81) and the lesson move of 2026-11-24 is complete (`lessons-moved.md` is its mapping)
- 2026-11-24 — **STANDING RULE: everything about plan-marshall leaves this epic for the plan-marshall repository (the operator's).** Any lesson, candidate lesson,
  tooling finding or leftover whose subject is plan-marshall itself, that is a component `plan-marshall:*` or a domain bundle of its marketplace (`pm-dev-java`,
  `pm-dev-java-cui`, `pm-dev-frontend`, `pm-dev-frontend-cui`, `pm-dev-oci`, `pm-dev-python`, `pm-documents`, `pm-plugin-development`, `pm-requirements` and any other
  `pm-*`), is NOT kept in this repository's lessons store, specs or Open Defects. It is filed into the `lessons-routing` inbox of `/home/oliver/git/plan-marshall`
  (`inbox write --slug lessons-routing --sender-type orchestrator --sender-id libraries-initial-implementation`, run from that repository, `--kind candidate-lesson`
  for a lesson body, `--kind finding` for a tooling observation), after reading that epic's phase (a closed or unreadable one is not written to and the item is held
  and reported to the operator). The local copy is retired only after the write returned success (`manage-lessons remove --coverage-verdict superseded`, the reason
  names the message), and the move is recorded in `lessons-moved.md` and the decision log. It applies at every drain of this epic's inbox (a `candidate-lesson` or
  `finding` message of a plan whose subject is plan-marshall takes the move instead of Promote or an Open Defect; the message's own disposition line says so), at every
  landing analysis (a tooling gap the analysis finds goes as a `finding`, with the evidence kept in the landing report), and at every `lessons` sweep. A lesson whose
  subject is this repository's modules (`git-core`, `git-store`, `git-forms`, ...) stays here, and so does a plan-marshall finding that is also a defect of this
  repository (it is split: the defect stays, the tooling part moves). The inbox files sit as untracked files in the plan-marshall ledger worktree until that
  repository lands its ledger; this orchestrator never touches that repository's git state.
- 2026-11-24 — **Change request (priority: high) filed** into the plan-marshall `lessons-routing` inbox as `libraries-initial-implementation-078` (a `finding`; the envelope has no
  priority field, so the title and the first line carry it): the `plan-orchestrator` skill and `manage-lessons` should apply the standing rule above themselves: a configured and validated
  framework home, one ownership predicate (`plan-marshall:*` and every installed marketplace bundle), a `forwarded` disposition in the `analyze` drain, forwarding of tooling gaps found in
  a landing analysis, the same predicate in the `lessons` sweep, a `relocated` verdict of `manage-lessons remove` that names the destination, idempotence on `source_lesson` and
  `source_repo`, and a `lessons-moved.md` record. Until it ships the rule is applied by hand by this orchestrator. The inbox read 122 live messages, 0 invalid, after the write.

- 2026-11-24 — **Second change request (priority: high) filed** into the plan-marshall `lessons-routing` inbox as `libraries-initial-implementation-079` (a `finding`): the
  `plan-orchestrator` should apply the standing routing rule itself (a fit predicate over `corpus surfaces`, `deliverables_total` and the queue status; a `corpus fold` write that
  appends a deliverable, extends the Expected Surface and stamps claims in one act and refuses a frozen plan; a container-plan template with a held-back field on the queue row and a
  configured deliverable bound of 10; `analyze` Step 4 and `handle-open` reading an epic-level `routing_rule`; the question kept for a real fork). Until it ships the rule is applied by
  hand here. The inbox read 123 live messages, 0 invalid, after the write.
> ↪ Relocated to `settled.md` § "Decisions 2026-11-25 — the PLAN-13 and PLAN-17 landings analysed and reconciled, the ledger landed through PR #78" — PLAN-13 and PLAN-17 shipped, analysed and routed (PLAN-14, PLAN-25, PLAN-26); the land of PR #78 is complete
- 2026-11-25 — **Inbox drain: `audit-notary-002` (finding, sender `audit-notary`) staged as PLAN-27** (`git-forms` stages within a document, five
  deliverables, held back), on the operator's answer to the fork (full concept, minimal alternative into PLAN-14 d8, or Watch only). The request: stages
  declared in `workflow.yaml` (a split part with `after`, `when`, `narrative`, `complete.step-up`; the status `not-applicable` and `open` derived,
  `completed` stored as `completion: {at, by}`), `complete` and `reopen` operations, and a `required` check at the first-state exit and at every release that
  covers only the document part and the applicable stages. Verified against `main` at `3e3cfc4`: GDR-FORMS-2 checks `required` on the whole document (175-181),
  `required` under `if`/`then`/`else` stays refused (391), and the workflow and the concept have no stage concept. Not verified: the consumer's own concept
  and its owner decision (cuioss/Audit-Notary was not read); the message cites the open question as 3 where the concept's list has it as the second item.
  The fold into PLAN-14 was declined because PLAN-14 has seven deliverables and the change alters GDR-FORMS-2 and -5; the operator decides at the PLAN-27
  outline between the full concept and the minimal alternative.

> ↪ Relocated to `settled.md` § "Decisions 2026-11-25 — the PLAN-22 landing analysed and reconciled" — PLAN-22 shipped, analysed and routed (PLAN-25, PLAN-24, PLAN-14); its 23 lessons are disposed of
- 2026-11-27 — **Operator decisions on the identity vocabulary** (after the PLAN-14 analysis; nothing staged yet). (1) The "mfa" naming: the whole identity model of
  `git-store` and `git-forms` is OpenID Connect vocabulary (`IdentityClaims(issuer, authTime, acr, amr)`, the trailers `Actor-Issuer`, `Auth-Time`, `Auth-Context`, the
  keys `step-up.acr` / `step-up.amr`, the default value `mfa` of RFC 8176), which contradicts the concept's own "Not in Scope: the OAuth flows and the identity
  provider". Of the three defaults offered, the operator chose **(b): no protocol default; a deployment must name what holds when verification is enabled.**
  (2) **TrailHand is to be renamed Audit-Notary everywhere** (`cuioss/TrailHand` redirects to `cuioss/Audit-Notary` on GitHub; the mentions in this repository are
  `AGENTS.md` 17-18 and `doc/README.adoc` 17-18). (3) The operator asked whether the library can be made fully abstract: "step-up" and "mfa" are too specific, a
  callback (`verifyIdentity`, or a `Function` passed in) so that the library has no knowledge of the mechanism at all. The analysis is in the session; the route (a new
  plan, and an ADR superseding the identity part of ADR-0005 and the issuer/`sub` part of ADR-0006) awaits the operator's word. PLAN-14 deliverable 6 (shipped, not
  released) and the layer-3 minor L3-6 (an enabled `StepUpPolicy` with no value) are affected; PLAN-15, PLAN-16 and PLAN-27 name the vocabulary.
- 2026-11-28 — **Operator word: "stage plan-14 defects", "check against local checked out Audit-Notary", "settle narrative", "land all changes and prepare to restart".** (1) The PLAN-14
  defects are staged as **PLAN-29** (see Queue annotations and Open Defects). (2) The check of PLAN-28 against the local `Audit-Notary` checkout (`11169dc`, read-only) refuted the
  hypothesis that no consumer declares the old names: no Java code uses the identity types, but its specification declares the workflow key `step-up`, the library's trailers and
  `STEP_UP_REQUIRED` as the contract; PLAN-28 absorbed it (the verdict is stamped). **Correction:** the landing of PLAN-14 and the open item (7) of the anchor said "ADR-0005 of
  cuioss/Audit-Notary says step-up is MFA by default"; in the checkout its ADR-0005 is about local users and a switchable step-up, and the "MFA by default" sentence is in THIS repository's
  ADR-0005 and concepts, which PLAN-28 rewrites. (3) **Narrative settled** (`cleanup` Phase B, the relocation the 2026-11-27 pass had deferred): the dated Decisions of 2026-11-18 to 2026-11-25
  that closed (plans shipped, landings analysed, lessons disposed of, findings routed) and the entries marked Retired or Closed in Open Defects and Watches were moved VERBATIM to
  `settled.md`, with a pointer at each origin; the standing rules, the foundation decisions of 2026-11-17, the Queue annotations, every open defect and every open watch stay. The
  Open Defects of PLAN-08 to PLAN-11 and PLAN-20 that read "open until PLAN-22 ships" or "until PLAN-24 ships" were NOT judged resolved and stay.

## Open Defects

> ↪ Relocated to `settled.md` § "Open Defects retired — PLAN-03, PLAN-19, PLAN-04, PLAN-06 and PLAN-12 (closed by PLAN-19, PLAN-20 and PLAN-23)" — the entries for PLAN-03, PLAN-19, PLAN-04 and PLAN-06 (marked Retired)
- PLAN-07 layer-3 defects in `git-store` (`landings/PLAN-07.md`, L3-1 to L3-5, all minor, with the layer-2 residue L2-1 to L2-3):
  `save.max-attempts` has no upper bound and `Integer.MAX_VALUE` makes `git-core`'s `ReferenceUpdater.swap` loop wrap (the PLAN-04
  `attempts` class; latent until PLAN-08's save uses the value), `StoreRepository.toString` keeps a token in a remote's path or
  query (GDR-NFR-3), a throwing candidate `id()` escapes `StoreProviders.select` as an exception, `StoreProviders.load` instantiates
  every `AnchorProvider` even when none is configured (and `GIT_STORE-1` logs ids against the Javadoc's own argument), the
  anchor-key equality is textual; the codec test uses a test-local codec, provider selection is tested on the class path only,
  and the domain-term list is copied into a fourth module. — routed on the operator's word (2026-11-20): L3-1 into PLAN-08 (deliverable 2, a
  bound for `save.max-attempts`, no new surface), L3-2 to L3-5 and L2-1 to L2-3 into PLAN-20 (new deliverable 7; moved to PLAN-22 deliverable 1 on 2026-11-22). L3-1 is closed:
  PLAN-08 shipped (PR #48) with the bound at 100, stated in `git-store.adoc`, refused as `MALFORMED` naming the key and tested at the
  bound, above it and at `Integer.MAX_VALUE`; the loop in `git-core` stays PLAN-20 deliverable 3 (c). The rest is open until PLAN-20 ships.
- PLAN-08 minor defects in `git-store` (`landings/PLAN-08.md`, L3-1 to L3-5 and L2-1 to L2-3): guard maps never pruned, a permanent
  rejection retried up to 100 times with no pause under the clone-wide locks, `NOT_FOUND` for a malformed `expectedBlobIds` value, a
  non-commit reference under `refs/drafts/` blocking all expiry, garbage collection protected in process only; the landing's false "no
  suppression" claim, an outcome-only ten-writers test, and the unenforced "declared twice" check. — routed to PLAN-20 (deliverable 8)
  on the operator's word (2026-11-21), moved to PLAN-22 deliverable 2 on 2026-11-22; open until PLAN-22 ships. The per-attempt save step
  the later plans need is not a defect of PLAN-08's spec: it was PLAN-09 deliverable 5, now shipped.
- PLAN-09 minor defects in `git-store` (`landings/PLAN-09.md`, L3-1 to L3-9, L3-11 and L2-1 to L2-5, L1-4): a complete release reported
  INCOMPLETE after a tag-push race, a local release tag left after a failed push of `main`, a gitlink entry that fails every release,
  an `Error` that escapes the five isolation points, executable and symlink modes dropped on save, an all-or-nothing index refresh, a
  save step that self-deadlocks through `expireDrafts`, a subscription race, a silent repair to an older state, the numbered release
  that copies instead of merging, a weak GDR-NFR-1 measurement and test gaps, the "pushed together" bullet of GDR-STORE-6 that
  `git-core` cannot meet, and stale document sentences. — routed to PLAN-22 (deliverable 3) on the operator's word (2026-11-22); open
  until PLAN-22 ships.
> ↪ Relocated to `settled.md` § "Open Defects retired — PLAN-03, PLAN-19, PLAN-04, PLAN-06 and PLAN-12 (closed by PLAN-19, PLAN-20 and PLAN-23)" — the entry for PLAN-12 (marked Closed)
- PLAN-23 defects in `git-forms` (`landings/PLAN-23.md`; one major, five minor): `DataNode.asObject` and `asArray` build a pointer string per
  child by copying the parent pointer, so a small document with long keys at depth and a wide mapping at the bottom costs gigabytes of strings
  (an `OutOfMemoryError` under an `additionalProperties`-through-`$ref` schema; the size is not shown); `SchemaChecks.holdsRequired` recurses
  along a `properties` → `$ref` chain that neither the depth limit nor the in-place chain bound covers (a `StackOverflowError` from a hostile
  kind schema); data depth times the in-place chain per level can still overflow the stack; a hexadecimal integer of 831 to 1000 digits is
  read but cannot be encoded (the reader counts digits in the source radix, the encoder in decimal); a caller-built shared-subtree value is
  walked as a tree (a hang); two registry aliases for one repository pass the duplicate-collection check; and the hostile in-memory value ends in
  an `IllegalArgumentException`, not a result. — routed to PLAN-14 (deliverable 5) under the routing rule on 2026-11-24 (PLAN-13 is running, so its spec is frozen; PLAN-14 is the next
  staged plan on `git-forms/`); open until PLAN-14 ships.
- PLAN-10 defects in `git-store` (`landings/PLAN-10.md`; ten minor, no blocker or major, with the layer-2 test gap L2-1 and the layer-1 residue
  L1-1 to L1-3): `AllowedSigners.read` has no size or line bound and `from` calls `Path.of` outside a try (an `OutOfMemoryError`, an
  `InvalidPathException` instead of a result, GDR-GEN-3); `SignatureVerification` strips every `gpgsig` header but verifies only the first
  (object-id malleability) and takes the validity time from the committer time inside the signed payload (a back-dated commit of an expired key is
  valid; Git's own semantics, but no revocation), and its `RevWalk` keeps the whole history in memory; the `Z` suffix is accepted where OpenSSH 8.8 and 8.9
  refuse it and one non-Ed25519 line disables the whole file; two log records name the reference (the document id); `IdentityClaims` throws for
  a token-derived `acr` or `amr`; a third-party `SigningKeySource` that throws is not isolated; `VerifySignatures` labels any
  `IllegalArgumentException` as "not a repository". — routed to PLAN-22 (deliverable 4) on the operator's word (2026-11-24, with the findings of PLAN-11); open until PLAN-22 ships.
- PLAN-11 defects in `git-store` (`landings/PLAN-11.md`; one major, eleven minor, with the layer-2 residue L2-1 to L2-3 and the layer-1 residue
  L1-2 to L1-5): `AnchorVerification` never compares a checkpoint's parent with the previous checkpoint (a moved checkpoint tag, a duplicate
  subject or a rewritten branch between two checkpoints verifies clean; the "never moved" invariant of GDR-STORE-16 is undetected); an unreadable
  checkpoint is reported clean; the verifier ignores `anchor.releases=false`; its cost is checkpoints times listed references; remote-controlled
  blobs are read with `getBytes(Integer.MAX_VALUE)`; `AnchorEngine.dueCheck` loses the collected fetch updates on a `RuntimeException`; an
  `Instant.plus` overflow with a huge configured duration; the INFO proof record is written before publication; an empty proof is accepted; a
  previous proof is chained unverified; `AllowedSigners.fromAnchor` calls `Path.of` outside a try; `StoreClone.close` has no closed flag; three
  in-code Sonar suppressions; raw threads in `AnchorInterleavingTest`; the mirrored set is not tied to `git-core`'s prune scope. — routed to PLAN-22
  (deliverable 4) on the operator's word (2026-11-24); open until PLAN-22 ships.
- PLAN-20 defects in `git-core` and `git-testing` (`landings/PLAN-20.md`; one major, four minor, with the layer-2 residue L2-1 to L2-6): the strict
  `ObjectChecker` of `InCoreWriter.checkPaths` is applied to a `Delete`, so a Windows-invalid name already stored in a tree (pushed from Linux)
  can no longer be removed through `InCoreWriter` or `compareAndSwap` (major); the duplicate and case-fold check ignores the parent tree and refuses
  a legitimate file-to-directory edit; `canonical()` treats every `FileSystemException` as missing (a symlink loop or an unreadable directory
  resolves through the ancestor); `storedOrigin` refuses a `//server/share/repo.git` path; `isBranchName` refuses any `refs/` prefix beyond git;
  no test pins the `HEAD` refusal of decision D3(d), the `rollBack` wiring is unobserved, the `Integer.MAX_VALUE` overflow fix has no test that can
  fail (the Done-when clause and deliverable 3(c) contradict), the hermetic fixture has one user, `AGENTS.md` omits `READ_FILE_LIMIT_BYTES`.
  — routed to PLAN-24 (deliverables 1 and 2), the new container plan for `git-core` and `git-testing`, under the routing rule on 2026-11-24
  (no staged plan edits those modules); open until PLAN-24 ships.
- Incomplete landings (PLAN-11 `plan-11-store-anchoring-signed-012.md` and PLAN-20 `plan-20-leftover-fixes-2-021.md`: both miss `effort` and the three review
  numbers (`unknown`: no finalize step records them); PLAN-11's `review-gate` re-read is undecidable (CodeRabbit "Minor comments: 1") and its Sonar read is
  undecidable (`head_revision_unavailable`, analysis revision `4db53c7`); PLAN-20's `review-gate` re-read is decidable (0 unresolved threads, 13 review-body
  findings, `handled: 0`, the handled count not re-verified: the progress file is gone) and its Sonar read is undecidable (analysis revision `5baca51`), so
  the `sonar_open_new_issues=0` of both is not re-verified; PLAN-10 `plan-10-store-signing-trailers-verify-015.md`: misses `effort` and the three review numbers (all `unknown`: no finalize step
  records them); its `review-gate` re-read is undecidable (CodeRabbit "Minor comments: 20") and the Sonar `0` is not re-verified; PLAN-01 `git-testing-fixtures-017.md`, PLAN-02 `git-core-import-013.md`, PLAN-18
  `plan-18-leftover-fixes-016.md`, PLAN-03 `plan-03-git-worktrees-import-020.md`, PLAN-19
  `plan-19-git-worktrees-hardening-018.md`, PLAN-04 `git-core-incore-write-cas-017.md`, PLAN-05
  `plan-05-git-core-merge-read-notes-013.md`, PLAN-06 `implement-plan-06-build-guards-014.md`, PLAN-07
  `plan-07-store-module-registry-spi-011.md`, PLAN-21 `plan-21-merge-worktrees-into-core-008.md`, PLAN-08 `plan-08-store-drafts-save-025.md`, PLAN-12 `plan-12-forms-kinds-schema-layout-004.md`, PLAN-23 `plan-23-forms-hardening-015.md`): `inbox landing-check` reports
  `complete: false`. PLAN-23 misses `effort` and the three review numbers (`unknown`); its `review-gate` re-read was decidable (0 unresolved
  threads, 1 review-body finding, `handled: 0`) and its Sonar read is undecidable (`head_revision_unavailable`, analysis revision `43dad67`),
  so `sonar_open_new_issues=0` is not re-verified. PLAN-12 misses the three review numbers and `sonar_open_new_issues` (all `unknown`: no finalize step records them; the
  lane names what it knows from the plan state: 0 open new-code issues on the merged head `a2792c7`); its `review-gate` re-read is
  undecidable (CodeRabbit's review-body section header) and the Sonar read is undecidable (`head_revision_unavailable`, analysis
  revision `a2792c7`), so nothing is re-verified. PLAN-09 was complete (the first). PLAN-08 misses `effort` only (the review and Sonar numbers were supplied: 0 unresolved threads, 2 body findings, 2
  handled, 0 open Sonar issues); its `review-gate` re-read is undecidable (the CodeRabbit "Minor comments: 10" section) and the Sonar
  read is undecidable (`head_revision_unavailable`, analysis revision `457160b2`), so none of the four is re-verified, and the lane's
  body-finding total (2) does not match the 11 findings its own lesson counts in the one review body (a difference of definition). PLAN-21 misses `effort` and the three review numbers (all `unknown`; `cleanup_owed=false` and
  `sonar_open_new_issues=0` were supplied); its `review-gate` re-read shows 0 unresolved threads and 1 review-body finding with no
  disposition (the lane's own account says four findings, three CodeRabbit and one Sourcery, were all handled; the handled count is not
  re-verified), and the Sonar read is undecidable (`head_revision_unavailable` on a merged PR), so `sonar_open_new_issues=0` is not
  re-verified either. PLAN-07 misses `cleanup_owed`, the three review numbers and `sonar_open_new_issues` (all `unknown`: no finalize
  step records them; `sonar-roundtrip` records `new_code_issue_count=0` under its own name); nothing was re-read. PLAN-06 misses the three review numbers; its `review-gate` re-read is decidable (0 unresolved threads, 3
  review-body findings, `handled: 0`) and the Sonar gate reads OK. PLAN-05 misses the three review numbers and `sonar_open_new_issues` (`effort` is `medium`, self-reported); its
  `review-gate` re-read is undecidable (the CodeRabbit "Minor comments: 1" section), the Sonar gate reads OK, and
  `archive-plan` is `unknown` in its steps. PLAN-04 misses `effort` and the three review numbers; its `review-gate` re-read was decidable (0 unresolved
  threads, 5 review-body findings, `handled: 0`), the Sonar gate reads OK (new coverage 87.3 %), and the lane's own statement
  (five CodeRabbit threads replied to and resolved) agrees with the 0 unresolved threads. PLAN-19 misses `effort` and the three review numbers; its `review-gate` re-read was decidable (0
  unresolved threads, 2 review-body findings, `handled: 0`, `body_findings_undisposed`), the Sonar gate reads OK and the
  hotspot read is empty. PLAN-01 misses
  `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`, `sonar_open_new_issues`;
  PLAN-02, PLAN-18 and PLAN-03 miss `effort` and the first three (all `unknown`: no finalize step records them); the PLAN-03
  `review-gate` read was undecidable too (CodeRabbit's "Minor comments: 7" section). Each landing was
  reconciled as far as it goes. The provider re-read gave PLAN-01 0 and 6, PLAN-18 0 unresolved threads and 12 body findings,
  and was undecidable for PLAN-02 (the review-body parser cannot read CodeRabbit's "Minor comments: 18" section); the handled
  count and the open Sonar count were not re-verified for any of them (the Sonar gate reads OK for PLAN-02 and PLAN-18). A
  manual paste from a plan may still surface a required fact the inbox did not carry. Recurring cause: a tooling gap, filed as a lesson.
- **PLAN-13's landing is incomplete** (`plan-13-forms-output-workflow-stepup-009`, drained 2026-11-25): `landing-check` reports `complete: false`, missing key
  `effort` (`effort=unknown`; the other required facts are present). The landing was drained and reconciled as far as it goes; a manual paste from
  that plan may still surface the effort. Its review-gate re-read is `undecidable` (the parser cannot read CodeRabbit's "Minor comments: 1"), a
  recurrence of the producer gap already filed to the plan-marshall inbox (`-075`), so the three review numbers are recorded as not re-verified.
- **PLAN-13's code defects, routed 2026-11-24 and 2026-11-25** (`landings/PLAN-13.md`): L3-1 (major: a `release` transition that fails after its save
  cannot be retried, `TRANSITION_REFUSED`), L3-3 (the fragment cost outside the operation budget), L3-4 (the save reads files the check refuses over
  16 MiB), L3-6 (a part at depth 129 throws instead of a result), L2-1 and L2-2 (the missing tests), and the info items (`isOutput` without a production
  caller, an unescaped path, the undocumented duplicate-transition-name refusal) are **PLAN-14 deliverable 7**; L3-2 (the pinned release on the
  tag-only recovery path returns an `OK` `Release` naming an earlier release) is **PLAN-25**; L3-5 (an `auth_time` in the future always passes) is kept
  by decision and recorded in `git-forms.adoc` by PLAN-14 d7. Each stays open until its plan ships.
- **PLAN-17's landing is incomplete** (`plan-17-timestamp-rfc3161-012`, drained 2026-11-25): `landing-check` reports `complete: false`, missing keys
  `review_body_findings_total` and `review_body_findings_handled` (both `unknown`: no finalize step records them, the producer gap already filed as
  `-075`). Reconciled as far as it goes; the review-gate re-read is `undecidable` (the parser cannot read CodeRabbit's "Minor comments: 16"), so the
  three review numbers are not re-verified.
- **PLAN-17's code defects and test gaps, routed 2026-11-25** (`landings/PLAN-17.md`): L2-1 (major, the core verification rules have no test that can
  fail), L2-2, L2-3, L3-1 to L3-4, L3-6, L3-7 and L1-1 are **PLAN-26**; L3-5 (the unbounded read of a stored proof) went to PLAN-25 and is **closed**: PLAN-22 bounded every blob read
  on 2026-11-25 (re-read in `git-store` main), and PLAN-26 deliverable 2(e) keeps only the provider's own bound. Each other item stays open until its plan ships.
- **PLAN-17 merged without the review the barrier counts** (2026-11-25): CodeRabbit (required) did not review `15afbb5` and `67b17d7` (quota ran out;
  operator's choice "Merge without it"); the barrier reported `review_complete_after_handling`, a plan-marshall defect filed as `-090`. The two
  commits are covered by this epic's three-layer reading of the merged module, which found no blocker; they are not covered by a bot review.
- **PLAN-22's landing is incomplete** (`plan-22-store-hardening-024`, drained 2026-11-25): `landing-check` reports `complete: false`, missing keys `effort`,
  `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` (all `unknown`: the runtime stamp has no effort and no finalize step
  records the review numbers, the producer gap `-075`). Reconciled as far as it goes; the review-gate re-read is `undecidable` (CodeRabbit's "Minor comments: 1"),
  so the review numbers are not re-verified. PLAN-22 also merged with the final head `09b57182` never reviewed by CodeRabbit (it paused itself; plan-marshall
  defect `-107`) and with three S4449 suppressions in `StoreConfiguration` (289, 309, 344) that carry a reason and no `operator-selected` record (PLAN-25's
  Standing Constraints put them to the operator).
- **PLAN-22's defects, routed 2026-11-25** (`landings/PLAN-22.md`): L3-1 and the store half of the recovery, L3-4 to L3-7, L3-9, L3-10, L2-2 and L2-3 are
  **PLAN-25**; L3-2 and L2-1 are **PLAN-14 deliverable 7(h)**; L3-3 and the `git-core` half of L3-4 are **PLAN-24 deliverable 3**; L3-8 (the Javadoc memory
  claim) is **PLAN-25 deliverable 2(e)**; L3-11 to L3-13 are recorded and not routed (a documented scheduled-runner rule, a cost that predates the plan, a CI
  main class). Each stays open until its plan ships.
- **PLAN-14 shipped; the defects recorded as "PLAN-14 deliverable N" are closed by PR #81** (2026-11-27, `landings/PLAN-14.md`): the PLAN-23 leftovers (d5), the
  PLAN-13 and PLAN-22 findings including the resume of a failed release and the `branchMovedOn` mapping (d7), the Audit Notary step-up switch (d6). The landing's layer-2
  reading traces each to code and a test that can fail, with the gaps below as residue; an item whose test is weaker than its wording (L2-3 to L2-7) is not closed in
  substance.
- **PLAN-14's landing is incomplete** (`plan-14-forms-deadlines-links-numbers-023`, drained 2026-11-27): `landing-check` reports `complete: false`, missing keys
  `effort`, `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` (all `unknown`, the producer gap `-075`). Reconciled as far as
  it goes; the provider re-read gives 0 unresolved threads and 6 body findings, the handled number is not re-verified (see the landing report).
- **PLAN-14's layer-3 defects, STAGED as PLAN-29 on the operator's word (2026-11-28)** (`landings/PLAN-14.md`; PLAN-29 is held back and runs after PLAN-28; none fixed; L3-6 is superseded by PLAN-28): **L3-1 (major)** a failed release after a
  narrative-only revision is never released, because `TransitionContext.unreleasedTip` compares only the document file with the release tags; **L3-2 (major)** a numbered
  entry whose first transition is a release loses its allocated key when the release fails (`TransitionSave` 124-127, 152-161), so a retry is refused or takes a second
  number; minors L3-4 (Period at an extreme instant dropped), L3-5 (one undecodable document blocks `due()`), L3-6 (enabled `StepUpPolicy` with empty `amr` and `acr`),
  L3-7 (the `from` guard weakened on resume), L3-8 (alias identity by remote string), L3-9 (fetch before the step-up check on resume); not shown by reading: L3-3, L3-10 to
  L3-12. The layer-2 test gaps are L2-1 to L2-7 and the unexplained files L2-8. Routed on the operator's word "stage plan-14 defects" (2026-11-28) into the new `git-forms`
  container plan **PLAN-29** (four deliverables: the two majors, the five minors, the four points reading could not settle, the test gaps and the GDR-FORMS-6 wording), not
  into PLAN-27, whose fold was declined once; PLAN-29 runs before PLAN-27.

## Watches

> ↪ Relocated to `settled.md` § "Watches retired — the leftovers of PLAN-18, the merge authorisation, the draft reference names, the per-attempt hook, the PLAN-22 rebase" — the PLAN-18 leftovers (marked Retired, routed to PLAN-20)
- PLAN-19 and PLAN-04 both edited `doc/specification/general.adoc` and the state lists. — observed at PLAN-04's landing: its
  rebase conflicted by hand in four documents (`doc/README.adoc`, `AGENTS.md`, `README.adoc`, `general.adoc`), both sides kept; no
  re-verify signal. Recorded in Queue annotations (PLAN-04) for the next pairing. **Recurrence (PLAN-10, 2026-11-23, candidate message 012):**
  PLAN-10's `phase-5-execute` found `origin/main` advanced by PR #59 and the merge-tree probe reported conflicts in `doc/README.adoc` and
  `doc/specification/general.adoc`; one `2-refine` envelope was spent on the reconcile. Every plan of the epic edits the implementation-state lines of those
  two files and the "Current state" paragraph of `AGENTS.md`, so plans that run at the same time conflict there whatever their module. The gate
  predicted the pair (both declared those files), so the pairing was already known. Open question for the operator, not decided here: a
  shared-surface rule (serialise such pairs, or defer the state-line edit to a landing step) or one line per module. **Recurrence (PLAN-20,
  2026-11-24):** PLAN-20 was rebased at `branch-cleanup` onto PLAN-11 (which edits `AGENTS.md`, `doc/README.adoc` and `module-structure.adoc`); the rebase
  was content-clean but voided four head-bound verdicts and cost a second finalize pass (lesson `2026-11-24-07-008`); the operator accepted the same
  overlaps again for the next block (PLAN-13, PLAN-17, PLAN-22 on `AGENTS.md`, `general.adoc` and `doc/README.adoc`). Still the operator's open
  question. Open.
- PLAN-06 is the last foundation plan and the 0.1.0 release follows it (an operator act through `/release`; a Maven Central release
  cannot be withdrawn). PLAN-20 changes the behaviour of public `git-core` and `git-worktrees` operations and adds guards. —
  question to the operator, open: release PLAN-20 before 0.1.0, or release 0.1.0 first and carry the fixes into the next version.
  PLAN-07 has landed: `git-store` is on `main` and in the reactor, so 0.1.0 would publish it unless the release is cut first or
  `git-store` is left out of the cut. PLAN-21 (the merge) has landed (PR #46), so the artifact `git-worktrees` no longer exists and the
  coordinates are settled before 0.1.0; PLAN-20 and the remaining `git-store` plans are what is still open against the cut. PLAN-08
  has landed (PR #48), so the cut would also publish the draft and save write path, with its public `FetchInput` record change in
  `git-core` (a canonical-constructor break, acceptable only because nothing is released yet); PLAN-09 has landed too (PR #52: release,
  index, listeners, the save step, and `TreeEdit.Mode` in `git-core`, and a test-scope JOL dependency), so the cut would publish those;
  PLAN-12 has landed too (PR #54) and PLAN-23 closed its two majors (PR #59): `git-forms` is on `main` and in the reactor, and its own
  analysis still carries one major (the pointer-string memory amplification) and five minors, unrouted; PLAN-10 has landed too (PR #58,
  `ad9e773`: every save and release commit is signed, the identity trailers, the verifier and its CI main class, no new dependency) with ten
  minors, now routed to PLAN-22; PLAN-11 has landed too (PR #66, `b30ec14`: the anchoring engine, checkpoints, proofs as notes, the provider `signed`
  and the verifier, with a public `AnchorProvider.obtain` signature change) with one major and eleven minors, routed to PLAN-22 deliverable 4; PLAN-20 has landed
  (PR #69, `f7eda4e`: `git-core` and worktrees hardening, `git-testing` fixtures, the fourth build guard, EasyMock at test scope in `git-core`) with one
  major of its own (a refused `Delete` of a Windows-invalid name) and four minors, unrouted; PLAN-22 (now four deliverables, emitted 2026-11-24) is what is
  still open against the cut, besides PLAN-13 (`git-forms`), PLAN-17 (`git-timestamp`) and the unrouted `git-forms` major of PLAN-23. The operator's #50 (2026-11-21) added the consumer
  propagation block to `.github/project.yml` (no version change).
- The committed architecture inventory `.plan/project-architecture/` still names the removed module `git-worktrees` (`_project.json`,
  `git-document-repository/enriched.json` and a whole `git-worktrees/enriched.json`) after PLAN-21 (`landings/PLAN-21.md` L2-4). It is
  derived data of the steward, not repository source, and `.plan/` was outside the plan's content search, so no Done-when is broken. —
  regenerate it with the plan-marshall architecture refresh (an operator or steward act, not a queue row); open until then. It can
  mislead module resolution (`manage-architecture which-module`) for paths of the merged package. PLAN-08's run raised the same gap
  from the other side: `git-store` has an undeclared-empty `skills_by_profile.implementation` and `.module_testing` (Q-Gate findings
  41262d and b4bc7f, accepted), so its tasks ran on persona skills only; the architecture refresh should enrich them (java-core,
  java-null-safety, javadoc, cui-logging; junit-core, cui-testing) or declare them minimal. PLAN-09 raised the same two findings again
  (Q-Gate 0f7b46 and 3f11a5, messages 001 and 002) and the orchestrator added the skills by hand: every `git-store` plan repeats it until
  the architecture refresh runs. PLAN-23 raised it for `git-forms` (Q-Gate a061c4 and 5af2f5, the operator accepted both, "no config
  change"), and the persona resolver reported `module_status=unreadable` for the module `documentation`: the same refresh settles
  `git-store`, `git-forms` and `documentation` at once, and every later `git-forms`, `git-directory` and `git-timestamp` plan will
  repeat the finding until it runs. PLAN-11 raised it for `git-store` again (Q-Gate findings 4ad387 and e95926, both accepted as informational, messages
  001 and 002 of 2026-11-23): the third `git-store` plan to do so.
> ↪ Relocated to `settled.md` § "Watches retired — the leftovers of PLAN-18, the merge authorisation, the draft reference names, the per-attempt hook, the PLAN-22 rebase" — the merge authorisation of PLAN-04's manifest and the draft reference names (both marked Retired)
- The first `git-core` consumer of `git-testing` (PLAN-02) ran on the class path; no module-path run exists anywhere in the
  reactor (recorded in `git-testing.adoc`). — re-check when PLAN-06 adds the layering guard.

- The `pm-mcp-core` switch-over (`doc/clients/pm-mcp-core.adoc` § Order of the Switch-Over, step 2) follows the
  0.1.0 release and is work in `~/git/pm-mcp-core`. Its open question 3 (JGit in the native image of `pm-mcpd`
  against the first SNAPSHOT) is also the only verification of GDR-NFR-4 until this repository has a native test lane.
  — re-check after the 0.1.0 release.
- New-module surfaces are unresolved until the module exists (`git-store/`, `git-forms/`, `git-directory/`,
  `git-timestamp/` are reported in `corpus surfaces` `unresolved[]`). Once PLAN-07 / PLAN-12 / PLAN-15 / PLAN-17 land
  the directory, the same entries resolve and the gate sees the in-module overlaps by itself. — re-check
  `corpus surfaces` at each module-creating landing. PLAN-07 has landed: `git-store/` resolves from `a558afd` on.
> ↪ Relocated to `settled.md` § "Watches retired — the leftovers of PLAN-18, the merge authorisation, the draft reference names, the per-attempt hook, the PLAN-22 rebase" — the per-attempt hook of PLAN-09 and the PLAN-22 rebase over PLAN-13 (both retired)
- **PLAN-17's Sonar issue java:S2143 needs a manual dismissal** (2026-11-25): key `AaIN1JmnF1H5L5GKrffy`, INFO, `TimeStampTokens` (`java.util.Date` is forced by `X509Certificate.checkValidity(Date)` and `PKIXParameters.setDate(Date)`; the leaf confirmed it by reading). Sonar refused the `falsepositive` transition twice with HTTP 400 and no reason (a plan-marshall defect, filed as `-089`), so it stays open on SonarCloud until the operator dismisses it on the page or the permission is fixed. PLAN-26 reads the count at its head. Retire when dismissed.
