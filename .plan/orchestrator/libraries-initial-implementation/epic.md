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
  PLAN-12's surface, `git-store/pom.xml` lies inside PLAN-09's `git-store/`). PLAN-12 is running (PR #54 open); PLAN-09 landed first, so its
  rebase onto PLAN-09's documents is still to come.
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
  of deliverable 4 and no `git-store` entry left in its Expected Surface.

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

- 2026-11-18 — Started PLAN-02 and PLAN-18 side by side (operator: "#2 is running", "#18 is running"; the gate showed no
  shared file). Both shipped the same day: PLAN-02 as PR #25 (`2c728bc`), PLAN-18 as PR #28 (`7418f76`); landing reports
  `landings/PLAN-02.md` and `landings/PLAN-18.md`. PLAN-18 was started by the operator although it was staged as held back,
  so its spec was frozen before the leftovers of PLAN-02 arrived.
- 2026-11-18 — Layer-3 routing of the two landings. PLAN-02: 1 major (GDR-CORE-1 met only in part by default; documented in
  the Javadoc and in the specification, but `doc/README.adoc` lists it as implemented) and 8 minor; four hardening residuals
  from its message `git-core-import-012`. PLAN-18: 2 minor, 5 minor gaps. Operator's choice, from three offered (stage a
  second collection plan; fold into PLAN-04 and PLAN-05; Watch only): fold into PLAN-04 and PLAN-05. Code-side items and the
  wording of the "implemented" claim went into PLAN-04 deliverable 5, test-side items and residuals 2 to 4 into PLAN-05
  deliverable 5, each with its Expected Surface entries and OBSERVED claims. The `git-testing` leftovers have no staged spec
  to join and are a Watch.
- 2026-11-18 — Surface correction at PLAN-02's landing (two lessons: the module-state statement lives in `AGENTS.md`,
  `README.adoc` and `doc/README.adoc`). `doc/README.adoc` was declared by no staged spec and `README.adoc` only by PLAN-06;
  every plan that implements GDR requirements or creates a module edits them. Added to the Expected Surface of PLAN-03 to
  PLAN-17: `doc/README.adoc` (all fifteen), `README.adoc` (PLAN-03, 07, 12, 15, 17), `AGENTS.md` (PLAN-04, 08 to 11, 13) and
  `doc/concepts/module-structure.adoc` (PLAN-03), all HYPOTHESIS. Consequence: the gate now sees the shared state paragraphs
  between plans that were declared disjoint before.
- 2026-11-18 — Candidate lessons of PLAN-02 (12) and PLAN-18 (15), under the operator's earlier rule for PLAN-01: nine new
  plan-marshall tooling lessons filed with `--allow-foreign-store` (`2026-11-18-13-001` to `-009`; the repeated findings
  merged, e.g. the change-ledger flag from both plans and the bot-quota waits), two existing lessons extended with the
  recurrences (`2026-11-18-07-001`: the documentation under-coverage again; `2026-11-18-07-006`: the keyless
  dispatch-boundary rows again), eleven lessons about the `git-testing` test code discarded as shipped, one (the four
  `git-core` residuals) routed as above.
- 2026-11-18 — Started PLAN-03 and PLAN-04 side by side (operator: "both are running"; the gate put the overlap on
  `AGENTS.md` and `doc/README.adoc` to the operator, who chose "Emit both"). PLAN-03 shipped the same day as PR #30
  (`e89cf86`); landing report `landings/PLAN-03.md`. PLAN-04 is still running. PR #30 rewrote the "Current state" paragraph
  of `AGENTS.md` and the module-state wording, so PLAN-04 meets that change when it rebases; the collision, if any, shows at
  PLAN-04's landing and is then recorded under the same-act rule.
- 2026-11-18 — Candidate lessons of PLAN-03 (19) under the operator's earlier rule: four new plan-marshall tooling
  lessons filed with `--allow-foreign-store` (`2026-11-18-18-001`: outline self-check for a criterion naming a file outside
  its deliverable and a forward reference to a missing open-question section, merging two messages; `-002`: `phase_handshake`
  drift after the phase's own Q-Gate resolution; `-003`: unmeasured build tiers pinned to the orchestrator tier; `-004`:
  configured-disabled build server logged at WARNING), two existing lessons extended with the third recurrence
  (`2026-11-18-13-004` the change-ledger flag, `2026-11-18-07-006` the keyless dispatch-boundary rows), twelve lessons about
  the `git-worktrees` code discarded as shipped (their rules are in the merged code, tests and specification).
- 2026-11-18 — Layer-3 report of PLAN-03: one major (`worktreeAdd` turns an unvalidated branch name into a ref path; a name
  such as `../../../x` writes a ref file outside `refs/`) and eight minor defects in `git-worktrees`, all in
  `landings/PLAN-03.md` (L3-1 to L3-9). No staged spec touches `git-worktrees` code (PLAN-06 touches its POM and resources
  only), so the routing was put to the operator. Operator's choice, from three offered (stage a hardening plan; stage a
  plan for L3-1 to L3-3 only; Watch only): stage a hardening plan. Staged as PLAN-19 (`git-worktrees-hardening`, WS-01,
  4 deliverables: input guards, result contract, races and path identity, tests), depends on PLAN-03; every item is first
  reproduced by a test that fails at the base. The ordinal is the next free one (the queue held PLAN-01 to PLAN-18).
- 2026-11-18 — PLAN-19 shares two files with the running PLAN-04 (`doc/specification/general.adoc`, and
  `GitOutcome.java` if a new outcome is needed for a refused branch name), so the gate put it to the operator, who chose
  "Emit now". Emitted, not recorded as launched or running (emit is not running; `auto_emit` is off).
- 2026-11-18 — PLAN-19 recorded launched, then running, on the operator's word ("#19 is running"); PLAN-04 and PLAN-19 then
  ran side by side.
- 2026-11-19 — PLAN-19 shipped (PR #31, merge commit `e2aced9`, `landings/PLAN-19.md`). 8 of the 9 PLAN-03 defects are
  closed (the major one, the unvalidated branch name, included), L3-9 (symlink aliases) only for paths that exist, and the
  documented outcome lists (L3-8) are still short in two places. The lane added one public operation,
  `releaseBranchReservation(Path, String)` (GDR-WT-4), and changed `worktreeRemove` to return `WORKTREE_NOT_FOUND`
  instead of `IDENTITY_MISMATCH` for a foreign repository at a registered path — both owner decisions taken during the run,
  not shims. `git-core` was not touched.
- 2026-11-19 — Candidate lessons of PLAN-19 (17) under the operator's earlier rule: eight new plan-marshall tooling lessons
  filed with `--allow-foreign-store` (`2026-11-19-06-001` `get-deliverable --number`; `-002` `review_completeness` bare versus
  pair flags; `-003` ci-verify transient Maven Central failure; `-004` the phase-5 completion line `4/10`; `-005` no
  build-duration row in the change-ledger; `-006` the per-deliverable commit trigger on a linear task chain; `-007` a
  scripted fails-at-base check; `-008` the test-identifier helper reads only pytest output), six existing lessons extended
  with a recurrence (`2026-11-18-18-001` the outline consistency defect, `2026-11-18-07-002` the invented `qgate query`
  verb, `2026-11-18-13-004` the change-ledger flag, now the fourth plan, `2026-11-18-13-005` the bot quota window,
  `2026-11-18-07-004` the criteria freeze before the outline's questions, `2026-11-18-18-004` the build-server WARNING),
  three lessons about the `git-worktrees` code discarded as shipped (the `LOCK_FAILURE` inference, the `Config.getEnum`
  exception, the reservation lifecycle: their rules are in the merged code and tests).
- 2026-11-19 — Layer-3 report of PLAN-19: one major (`IllegalArgumentException` from `Config.getEnum` still escapes
  `commit`, `worktreeStatus` and `worktreeRemove`, for example with `core.autocrlf = bogus`; TASK-11 fixed one reach of the
  class, the reflog) and seven minor defects in `git-worktrees` (`landings/PLAN-19.md`, L3-1 to L3-8). Reported to the
  operator at once; no spec is staged until the operator says how to route them.
- 2026-11-19 — PLAN-04 (PR #35, merge commit `c8feb0b`) landed; its 17 inbox messages (16 candidate lessons, one landing)
  drained. Lessons under the operator's earlier rule: three new plan-marshall tooling lessons filed with
  `--allow-foreign-store` (`2026-11-19-14-001` the closed `--termination-cause` enum paraphrased as `completed`; `-002` the
  hand-typed closing token delta that cannot be corrected; `-003` an under-coverage Q-Gate finding resolved
  `taken_into_account` leaves the Affected files unchanged, which also carries the plan-time helper rule of message 004);
  five existing lessons extended with a recurrence (`2026-11-18-07-001` the root README missing from a state-change
  deliverable, `07-002` the invented `qgate query` verb, now the third plan, `2026-11-19-06-001` `--number` for
  `--deliverable-number`, `2026-11-18-07-006` keyless dispatch-boundary rows, now the fourth plan, `13-004` the change-ledger
  flag, now the fifth plan). The review-defect lessons of the shipped `git-core` code (`failure(e)` instead of
  `getSimpleName()`, `parseAny` instead of `parseCommit`, `updateRef(name, true)`, and the threaded compare-and-swap test on
  the local fixture) were folded into PLAN-05 as one Standing Constraints rule, because PLAN-05 adds notes, merge and read
  code in the same package and a notes-concurrency test; its expected surface is unchanged (no file added). Two lessons
  about the shipped tests (the retry seam, the digit-prefixed absent-value assertion) and the uppercase-scheme URL leak,
  already fixed and tested in the merged code, were discarded.
- 2026-11-19 — PLAN-04 shipped (PR #35, merge commit `c8feb0b`, `landings/PLAN-04.md`): in-core writing (GDR-CORE-4),
  compare-and-swap and compare-and-delete (GDR-CORE-5) and hardening items (b), (c), (e), (f), (g), (h); (a), (d), (i) are
  accepted exceptions in `git-core.adoc`. All seven spec claims corroborated; the Sonar gate reads OK, the review gate 0
  unresolved threads and 5 body findings (`handled` not re-verified). The merge was held for the owner because the execution
  manifest carries `final_merge_without_asking=true` against the AGENTS.md rule (see Watches).
- 2026-11-19 — Layer-3 report of PLAN-04: one major (`dircache.InvalidPathException`, an `IllegalArgumentException`, escapes
  `InCoreWriter.write` and `compareAndSwap` for a parent tree holding an entry such as `.GIT`) and eight minor defects in
  `git-core`, plus three info points and four layer-2 residues (`landings/PLAN-04.md`, L3-1 to L3-12, L2-1 to L2-8). Reported to
  the operator in the same turn. Routing: on the operator's word ("make it one plan for other leftovers again, not only a single
  fix plan") the layer-3 defects of PLAN-04 and PLAN-19 and the `git-testing` Watch of PLAN-18 were collected into one
  held-back plan, PLAN-20 (`leftover-fixes-2`, WS-01, 5 deliverables, below the split guard), staged with
  `queue --add-row`; the ordinal is the next free one (the queue held PLAN-01 to PLAN-19). Not emitted and not released: the
  operator releases it by naming it in a `next` round. Two items of it end in an owner decision (the platform dependence of the
  path check, the size bound of `readFile`).
- 2026-11-19 — PLAN-05 recorded launched, then running, on the operator's word ("#5 is running"). It had already merged when the
  next `analyze` ran (see the PLAN-05 entries below).
- 2026-11-19 — Candidate lessons of PLAN-05 (12) under the operator's earlier rule: four new plan-marshall tooling lessons filed
  with `--allow-foreign-store` (`2026-11-19-19-001` the Sonar test smells no pre-push check catches, `-002` a fix task created
  with empty skills and no verification commands, `-003` outline questions that recommend first-principles options over the
  project's BOM-managed test library, `-004` `git-workflow commit` reporting `FETCH_FAILURE` for an empty index); five existing
  lessons extended with a recurrence (`2026-11-18-13-002` the finalize commit guard `no_sanction`, `13-004` the change-ledger
  flag, now the sixth plan, `13-003` `manage-findings list --limit`, `2026-11-19-06-005` no build row in the change-ledger,
  `2026-11-18-07-006` keyless dispatch-boundary rows, now the fifth plan); one folded into PLAN-20 (tests that depend on the
  developer's git configuration: a `git-testing` item of deliverable 5, one Standing Constraints sentence and one claim, no
  surface change because `git-testing/` is declared); two discarded as shipped code (the symbolic-reference fallback and the
  JGit iteration, filter and inserter behaviours behind the `RepositoryReader`, `InCoreWriter` and `MergeResult` fixes).
- 2026-11-19 — PLAN-05 shipped (PR #37, merge commit `14269fe`, `landings/PLAN-05.md`): in-core merge with a sealed
  `MergeResult` conflict carrier, reading (`RepositoryReader`), git notes (`GitNotes`) over a package-private
  `compareAndSwapTree`, and the residuals (a) to (f) of PLAN-02; all six spec claims corroborated; the Sonar gate reads OK
  (new coverage 96.9 %), the review gate is undecidable (CodeRabbit's "Minor comments: 1" section). The folded PLAN-04 review
  rules were applied except `parseAny` in `ReferenceUpdater.parseCommit`.
- 2026-11-19 — Dependency decision of PLAN-05: `de.cuioss.test:cui-test-mockwebserver-junit5` (test scope of `git-core`, BOM-managed,
  for the end-to-end redirect test) was added on the operator's answer at the outline question ("why not cuihttpmock"); the
  lane recorded the approval (2026-11-19) in `general.adoc` and `git-core.adoc`. No independent decision record exists, so
  this entry is the ledger's. The approved list in `AGENTS.md` is unchanged.
- 2026-11-19 — Layer-3 report of PLAN-05: no blocker and no major; four minor defects re-read (`InCoreMerger.merge` lets
  `InvalidPathException` escape, `JGitRepositoryOperations.log` reads past `maxCount` and lets `RevWalkException` escape, a notes
  reference that name-conflicts is reported as a lost race, the `unresolvedUnderLock` Javadoc names the wrong discriminator),
  two minor leads and two points not shown by reading. Reported to the operator at once and folded into PLAN-20 in the same
  act (items 3 (a), (b), (d), (h), deliverable 4 (d) and (e), claims 15-18); the Done-when base of PLAN-20 is now `14269fe`.
- 2026-11-20 — PLAN-06 recorded launched, then running, on the operator's word ("#06 is running"). It had already merged when the
  next `analyze` ran.
- 2026-11-20 — PLAN-06 shipped (PR #39, merge commit `e43c7e9`, `landings/PLAN-06.md`): the layering guard (`module-layering`, the
  built-in `bannedDependencies` rule, one include per edge in each module's POM), the consumer check as an Enforcer execution
  `consumer-isolation` inside `git-core` instead of a test consumer project (the owner confirmed the substitution during the
  run), the domain-term test in three modules, the `git-core` native-image metadata (one class, one bundle) and no
  `git-worktrees` metadata (the operator's decision after research: an empty `resource-config.json` is invalid GraalVM format).
  All four spec claims corroborated; no new dependency (the plugin and its rules come from `cui-parent-pom` 1.7.7); the Sonar
  gate reads OK, the review gate 0 unresolved threads and 3 body findings (`handled` not re-verified).
- 2026-11-20 — Candidate lessons of PLAN-06 (14) under the operator's earlier rule: five new plan-marshall tooling lessons filed
  with `--allow-foreign-store` (`2026-11-20-07-001` a substituted artifact without a recorded deviation, `-002` a deliverable's
  module not matching its affected files, found in two phases and carrying four messages, `-003` an unrecorded question and an
  unchecked external-format decision, `-004` `architecture which-module` returning null for resource paths, `-005` a dropped
  deliverable that stays live in the outline); three existing lessons extended (`2026-11-18-13-004` the change-ledger flag, now
  the seventh plan, `2026-11-18-07-002` `qgate list` without `--phase`, `2026-11-18-13-009` an infeasible leaf return recorded as
  `termination_cause: error`); two folded into PLAN-20 (the recurring generated-value flake, now with a `git-testing` item, and the
  review's accepted hand-mirrored lists with the confirmed optional-dependency hole, which became deliverable 6); one discarded
  (the outline's false premise about DFS storage, caught by the Q-Gate and already required by CLAUDE.md).
- 2026-11-20 — Design question of the operator: does the separation of `git-worktrees` from `git-core` make sense, it being a core
  git feature? Findings put to the operator: the documented reason (ADR-0001, the store does not need working trees) is an
  audience and attack-surface argument; the usual reason for a module, separate third-party dependencies, does not apply
  (`git-worktrees` adds none); the split is leaky (`GitOutcome` in `git-core` carries four worktree outcomes plus two more it uses,
  and PLAN-04 and PLAN-19 collided on it); the module is about 1,700 lines against 5,000; and the "store cannot reach working-tree
  code" guarantee is only structural, because JGit's checkout API is on the class path anyway. Recommendation given: merge it into
  `git-core` as a package before 0.1.0, unless the audit claim is wanted. The operator asked for a plan.
- 2026-11-20 — PLAN-21 staged (`merge-worktrees-into-core`, WS-01, 4 deliverables, below the split guard) on the operator's word ("create a
  plan for doing the merge, adapt all documentation as part of it"); the ordinal is the next free one (the queue held PLAN-01 to
  PLAN-20). Defaults decided in the spec and put to the owner at its outline: the package `de.cuioss.git.document.core.worktrees`
  (kept encapsulation: `LinkedWorktrees` is package-private, and the boundary the ADR relies on; the flat form into the existing
  package is possible, no main type name collides, only two test classes share names across the modules), the requirement ids `GDR-WT-1` to `GDR-WT-4` kept inside
  `git-core.adoc`, the log identifiers merged under `GIT_CORE` (both prefixes collide by number), and a new ADR amending ADR-0001
  and ADR-0004. Nine claims stamped corroborated, two hypotheses open (no reflection in the moved code, the `consumer-isolation`
  allow-list still passing). PLAN-20 was made to depend on it. Not emitted.
- 2026-11-20 — Owner decision for PLAN-21: the package is `de.cuioss.git.document.core.worktrees` (the operator's word, after the
  alternatives were put to them: the flat form and a second package `de.cuioss.git.document.worktrees`). It is recorded in the
  spec as decided and is not asked again at the outline; the remaining defaults (requirement ids, log identifiers, ADR form)
  are still put to the owner there.
- 2026-11-20 — PLAN-07 recorded launched, then running, on the operator's word ("#07 is running"), the same turn PLAN-21 was staged. The
  operator started it after the caution that the modules are released together; PLAN-21 therefore follows PLAN-07 unless the
  operator accepts the five-file overlap at the pairing prompt.
- 2026-11-20 — Layer-3 report of PLAN-06: no blocker and no major; four minor defects (a direct optional dependency of `git-core`
  bypasses `consumer-isolation`, confirmed in the Enforcer 3.6.3 bytecode; the layering guard is self-policing; the domain-term
  list is copied by hand into three tests; `NativeImageMetadataTest` checks names, not shape) and two points not shown by reading
  (build-guard bypasses and CI skip flags, native-image completeness). Reported to the operator at once and folded into PLAN-20
  deliverable 6 in the same act; no new plan is staged.
- 2026-11-20 — PLAN-07 landed (PR #41, `9bdc5ba`) and was analysed on three layers: no blocker, no major; five minor layer-3 defects
  in `git-store` (an unbounded `save.max-attempts` that reaches `git-core`'s `attempt <= attempts` loop, a token in a remote's query
  rendered by `toString`, a throwing candidate `id()` escaping `StoreProviders.select`, every `AnchorProvider` instantiated even
  when none is configured, a textual anchor-key comparison), three layer-2 minors (the codec test uses a test-local codec, provider
  selection is tested on the class path only, the domain-term list is copied into a fourth module) and one premise narrowed
  (a duplicate id stops the start only for the configured id; the spec was rewritten). Reported to the operator at once; recorded as
  an Open Defect and routed on the operator's word: L3-1 into PLAN-08, the rest into PLAN-20 as a seventh deliverable (over the
  split guard, proceeding unsplit on the same ground as before; the operator may split it when releasing it). The ten lessons: nine
  recurrences, two new outline lessons (`2026-11-20-14-001`, `-002`), one not promoted (no log corroborates it).
- 2026-11-20 — Emit round after PLAN-07 (nothing in flight): PLAN-08 and PLAN-21 both pass the three admission tests, and share three files
  (`AGENTS.md`, `doc/README.adoc`, `doc/specification/general.adoc`). The operator chose PLAN-21 only: the merge comes first (artifact
  coordinates before 0.1.0, PLAN-20 depends on it), PLAN-08 stays staged and is emitted when PLAN-21 lands, so there is no hand merge.
- 2026-11-20 — PLAN-21 recorded launched, then running, on the operator's word ("#21 is running"); `auto_emit=false`, so `launched` was
  not recorded before the operator confirmed. The outline's three owner questions were answered by the operator (renumber the
  requirement ids, merge the log ids under `GIT_CORE` with the worktrees refusal as its own record, rewrite ADR-0004 in place with no new
  ADR), which differ from the defaults the spec proposed; the outline then re-froze the success criteria on its own authority and the
  operator approved that afterwards through the question tool (the lesson `2026-11-20-07-001`, extended).
- 2026-11-20 — PLAN-21 landed (PR #46, `9e3cfb0`) and was analysed on three layers: no blocker, no major, no layer-3 defect (the move is
  behaviour-neutral; the one source change, the S4449 null-check, is neutral); layer 2: one deliberate minor (the worktrees refusal
  and `CAPABILITY_REFUSED` carry the same template under ids 102 and 101), one minor residue (the committed
  `.plan/project-architecture/` inventory still names the removed module, see Watches) and two documentation infos folded into PLAN-20
  deliverable 2 (e). Layer 1: the spec's own text (the `GDR-WT-n` display names, no new ADR, "root pom lists `git-core` and `git-testing`
  only") differs from the owner decisions and the base, info. Not shown by reading: the build, the 883 to 882 test count, the Sonar read
  (undecidable on a merged PR), and whether JGit commands the package drives reach a translation bundle beyond `JGitText` (GDR-NFR-4).
  PLAN-20 was re-grounded in the same act. The seven candidate lessons: six recurrences, one new (`2026-11-20-17-001`, the realized
  footprint drops rename sources), plus one new from the landing residue (`2026-11-20-18-001`, the merge-queue squash message carries
  the PR body with the review bot's release-notes block).
- 2026-11-21 — PLAN-08 recorded launched, then running, on the operator's word ("#8 is running"; `auto_emit=false`). The operator asked
  whether more could run in parallel: every staged plan is a dependency chain (PLAN-09..11 follow PLAN-08 on `git-store/`; PLAN-12 needs
  PLAN-08; PLAN-13..17 follow the module chain; PLAN-20 shares four declared entries with PLAN-08 and its deliverable 3 changes
  `ReferenceUpdater`, which PLAN-08 consumes), so the operator chose PLAN-08 alone, then PLAN-09 and PLAN-12 together.
- 2026-11-21 — PLAN-08 landed (PR #48, `c1e02fc`) and was analysed on three layers: no blocker, no major; layer 3: five minor points in
  `git-store` (the guard maps never pruned, a permanent rejection retried up to the bound with no pause, `NOT_FOUND` for a malformed
  expected blob id, a non-commit draft reference blocking all expiry, garbage collection safe only in process) and one info (the
  pruning fetch deletes local-only tags and notes); layer 2: three minor (the landing's "no suppression was used" is false — one
  `@SuppressWarnings("java:S4449")` in main code and two in tests; the ten-writers test asserts the outcome only; `git-core` was edited
  against the outline), infos (an unenforced "declared twice" check, no dedicated test of "store-written commits never combined", seven
  outline deliverables for five specified). Layer 1: the premises of three later specs are contradicted — PLAN-13 and PLAN-16 assume a
  per-attempt save step the save does not have, PLAN-15 assumes a reusable Crockford encoder that `Ulid` keeps private. Not shown by
  reading: the build, the Sonar read and the review numbers (both reads undecidable on a merged PR), the JGit side of the garbage
  collection. Reported to the operator at once. **The operator's answers (2026-11-21):** the per-attempt save step is folded into PLAN-09
  as deliverable 5 (GDR-STORE-3 amended); PLAN-09 and PLAN-12 are emitted together despite the three-file overlap; the layer-3 and
  layer-2 leftovers go to PLAN-20 as deliverable 8. PLAN-15 carries its own encoder (a store gap otherwise). The 24 candidate messages:
  4 recurrences extended, 2 new lessons (`2026-11-21-12-001`, `-002`), 2 folded into the architecture-inventory Watch (empty
  `skills_by_profile` of `git-store`), 3 folded into PLAN-09 to PLAN-12 as Standing Constraints (the review-round lessons), 13 discarded
  as lessons about shipped code.
- 2026-11-21 — PLAN-09 and PLAN-12 recorded launched, then running, on the operator's word ("both are running"; `auto_emit=false`).
- 2026-11-22 — PLAN-09 landed (PR #52, `4a5eed5`) and was analysed on three layers: no blocker, no major; layer 3: nine minor points in
  `git-store` (a complete release reported INCOMPLETE after a tag-push race, a stale local release tag after a failed push, a gitlink that
  fails every release, `Error`s escaping the five isolation points, modes dropped on save, an all-or-nothing index refresh, a save step
  self-deadlocking through `expireDrafts`, a subscription race, a silent repair to an older state); layer 2: five minor (the numbered
  release copies the entry's paths instead of merging, a weak GDR-NFR-1 measurement, missing release tests, stale document sentences);
  layer 1: the GDR-STORE-6 bullet "pushed together" and the relative GDR-NFR-1 text are flaws of the spec itself. The landing is the first
  `complete: true` of the epic; the review-gate and Sonar reads are undecidable on a merged PR. Reported to the operator at once.
  **The operator's answers (2026-11-22):** the defects go to a new PLAN-22 (`git-store` hardening), which also takes PLAN-20's former
  deliverables 7 and 8 (PLAN-20 keeps six); PLAN-10 is emitted beside the running PLAN-12 despite the four-file overlap (`pom.xml`,
  `general.adoc`, `AGENTS.md`, `doc/README.adoc`). The 28 candidate messages: 8 recurrences extended, 6 new lessons
  (`2026-11-22-13-001` to `-006`), 2 folded into the architecture-inventory Watch, 10 folded into PLAN-10 and PLAN-11 as Standing
  Constraints, 2 folded into PLAN-20 and PLAN-22 (the flaky tests, the duplicates and the JOL scope).

## Open Defects

- PLAN-03 layer-3 defects in `git-worktrees` (`landings/PLAN-03.md`, L3-1 to L3-9; one major, eight minor), including the
  open item the lane itself stated (`worktreeList` on a bare repository throws `NoWorkTreeException`, confirmed and wider:
  it also reaches `worktreeRemove` and `commit`). — routed to PLAN-19, which shipped (PR #31); its landing analysis confirms
  L3-1 to L3-7 closed and L3-8 and L3-9 partly; the residue is the entry below. Retired.
- PLAN-19 layer-3 defects in `git-worktrees` (`landings/PLAN-19.md`, L3-1 to L3-8; one major, seven minor): the major one is
  an `IllegalArgumentException` from a malformed `core.*` enum in the repository config (`core.autocrlf = bogus`) that
  escapes `commit`, `worktreeStatus` and `worktreeRemove` instead of ending as a `GitResult` (GDR-GEN-3); the minor ones
  are an `UncheckedIOException` that skips the rest of the add rollback (created branch and reservation stay), a failed
  reservation release on the success path that tears down a finished worktree, `realPath` I/O failures and a missing-path
  alias that is not resolved, a reflog catch narrower than its failures, two stale Javadoc sentences, and the branch-name
  rule being JGit's, not git's. The always-`PATH_REFUSED` add on a bare root is an undocumented behaviour change (layer 1).
  — routed to PLAN-20 (deliverables 1 and 2) on the operator's word; open until PLAN-20 ships.
- PLAN-04 layer-3 defects in `git-core` (`landings/PLAN-04.md`, L3-1 to L3-9; one major, eight minor): the major one is the
  `dircache.InvalidPathException` from `DirCache.read` that escapes `InCoreWriter.write` and `compareAndSwap` instead of ending
  as a `FAILED` result (GDR-GEN-3); the minor ones are unchecked exceptions of a `Signer`, `GpgConfig` or `TreeOperation`, a retry
  loop that never ends for `attempts = Integer.MAX_VALUE`, a name conflict retried as a lost race, an unvalidated `refName`, a
  file and a directory below it in one edit silently merged, a platform-dependent path check, a query-string token or an
  uppercase-scheme credential kept in the clone's stored origin, and an unbounded `readFile`; with the layer-2 residues (untested
  `notWritten` branches, no reflog test, `ReferenceUpdater.parseCommit` peels a tag). PLAN-05 (PR #37) left all of them in place
  and added the same defect class to its new code (`landings/PLAN-05.md`, L3-1 to L3-8). — routed to PLAN-20 (deliverables 3 and 4)
  on the operator's word; open until PLAN-20 ships.
- PLAN-06 layer-3 defects in the build guards (`landings/PLAN-06.md`, L3-1 to L3-4, with the layer-2 residue L2-1 to L2-3):
  `consumer-isolation` does not see a direct optional dependency, the layering rule is self-policing, the domain-term list is
  triplicated by hand, `NativeImageMetadataTest` checks names and not shape, the guards' negative runs live only in the squash
  message, and the native-image wording overclaims one class. — routed to PLAN-20 (deliverable 6); open until PLAN-20 ships.
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
- Incomplete landings (PLAN-01 `git-testing-fixtures-017.md`, PLAN-02 `git-core-import-013.md`, PLAN-18
  `plan-18-leftover-fixes-016.md`, PLAN-03 `plan-03-git-worktrees-import-020.md`, PLAN-19
  `plan-19-git-worktrees-hardening-018.md`, PLAN-04 `git-core-incore-write-cas-017.md`, PLAN-05
  `plan-05-git-core-merge-read-notes-013.md`, PLAN-06 `implement-plan-06-build-guards-014.md`, PLAN-07
  `plan-07-store-module-registry-spi-011.md`, PLAN-21 `plan-21-merge-worktrees-into-core-008.md`, PLAN-08 `plan-08-store-drafts-save-025.md`): `inbox landing-check` reports
  `complete: false`. PLAN-08 misses `effort` only (the review and Sonar numbers were supplied: 0 unresolved threads, 2 body findings, 2
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

## Watches

- Leftovers of PLAN-18's own landing in `git-testing`, with no staged spec to join. The EasyMock part (the spec premise,
  `git-testing.adoc:140` saying it needs approval, the exception reasons that were partly convenience) was settled outside
  the queue by PR #29 (`b5ee5d7`, "use EasyMock for the repository doubles": EasyMock in test scope, the table of accepted
  exceptions rewritten to the doubles that stay hand-written). #29 also rewrote the two extension tests, so the items below
  are as of PLAN-18's landing and not re-read against it: `GitCredentialGeneratorTest` never held a double; the positive half of
  the `@InMemory` type assertion is tautological; the rollback wiring `createBareRepository` to `rollBack` is unobserved;
  nothing asserts that the "auto-close off" Surefire run received its setting; `TemporaryRepository.close` loses the first
  failure when closing and deletion both fail; the public Javadoc of the three extensions is stale under `PER_CLASS`
  (`landings/PLAN-18.md`, L1-5, L1-6, L2-1 to L2-5, L3-1, L3-2). — fold into the next staged spec that touches
  `git-testing/`, or stage a second collection plan on the operator's word. — routed to PLAN-20 (deliverable 5), the second
  collection plan, with a verify-first clause because #29 changed two of the tests since. Retired.
- PLAN-19 and PLAN-04 both edited `doc/specification/general.adoc` and the state lists. — observed at PLAN-04's landing: its
  rebase conflicted by hand in four documents (`doc/README.adoc`, `AGENTS.md`, `README.adoc`, `general.adoc`), both sides kept; no
  re-verify signal. Recorded in Queue annotations (PLAN-04) for the next pairing. Retired.
- PLAN-06 is the last foundation plan and the 0.1.0 release follows it (an operator act through `/release`; a Maven Central release
  cannot be withdrawn). PLAN-20 changes the behaviour of public `git-core` and `git-worktrees` operations and adds guards. —
  question to the operator, open: release PLAN-20 before 0.1.0, or release 0.1.0 first and carry the fixes into the next version.
  PLAN-07 has landed: `git-store` is on `main` and in the reactor, so 0.1.0 would publish it unless the release is cut first or
  `git-store` is left out of the cut. PLAN-21 (the merge) has landed (PR #46), so the artifact `git-worktrees` no longer exists and the
  coordinates are settled before 0.1.0; PLAN-20 and the remaining `git-store` plans are what is still open against the cut. PLAN-08
  has landed (PR #48), so the cut would also publish the draft and save write path, with its public `FetchInput` record change in
  `git-core` (a canonical-constructor break, acceptable only because nothing is released yet); PLAN-09 has landed too (PR #52: release,
  index, listeners, the save step, and `TreeEdit.Mode` in `git-core`, and a test-scope JOL dependency), so the cut would publish those;
  PLAN-10, PLAN-11, PLAN-22 and PLAN-20 are what is still open against it. The operator's #50 (2026-11-21) added the consumer
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
  the architecture refresh runs.
- The execution manifest of PLAN-04 carried `final_merge_without_asking=true`, which contradicted the AGENTS.md rule "do not merge
  unless the user says so". — resolved by the operator's #42 (`3c609a7`): AGENTS.md on `main` now authorises a plan-marshall run
  to merge its own pull request once the checks are green and every review comment is handled (never a PR that changes
  `release.current-version`); PLAN-07's lane merged #41 through the merge queue under it. The ledger worktree's copy of AGENTS.md
  is still the old text until the next land resyncs it. Retired.
- The store's draft references may embed a person-bound identifier; `compareAndSwap` and `compareAndDelete` put the reference
  name in result details and DEBUG logs (`ReferenceUpdater` 127, 205, `landings/PLAN-04.md` L3-12), while GDR-NFR-3 forbids a
  person name or `sub` there. — re-checked at PLAN-08's landing (2026-11-21): a draft reference is `refs/drafts/<pseudonym>/<collection>/<id>`
  with a regex-validated pseudonym (`[A-Za-z0-9-]+`, the `p-…` form) and `ReferenceUpdater` (214, 216, 224, 295-302) still puts the name
  in details and DEBUG records; GDR-NFR-3 (`general.adoc` 265-270) forbids a name, `sub` and an address, not a pseudonym, so it is not
  broken. Retired.
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
- Plans after PLAN-08 assume `git-store`'s save exposes a per-attempt hook (regenerating output, PLAN-13; first-login
  checks, PLAN-16). If PLAN-08 lands without one, that is a store gap to stage, not to absorb in a forms or directory
  plan. — re-checked at the PLAN-08 landing (2026-11-21): the shipped `StoreWriter.save` has no per-attempt step, so the hypothesis of
  PLAN-13 and PLAN-16 was contradicted. A store gap, staged by the operator's word as PLAN-09 deliverable 5 (GDR-STORE-3 amended);
  PLAN-13 and PLAN-16 name it in their Dependencies. Retired.
