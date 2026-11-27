# Settled narrative: libraries-initial-implementation

> Relocated from `epic.md` by the `cleanup` verb (ledger-compaction stage, Phase B) on 2026-11-28, on the operator's word ("settle narrative"). Each section is moved
> VERBATIM; `epic.md` keeps a pointer at the origin naming the heading below. A section is here because its subject is closed (a plan that shipped, a landing that was analysed and
> routed, a lesson drain that was disposed of), never because it is old. Standing rules, the foundation decisions of 2026-11-17, the annotation zone, the open defects and the watches
> stay in `epic.md`. Nothing here is a source of truth for the queue: the ledger files are.

## Decisions 2026-11-18 — PLAN-02, PLAN-18, PLAN-03, PLAN-04 and PLAN-19: started, analysed, routed

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

## Decisions 2026-11-19 — PLAN-19, PLAN-04 and PLAN-05: landings, lessons and routing

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

## Decisions 2026-11-20 — PLAN-06 shipped, the worktrees merge decision, PLAN-21 staged

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

## Decisions 2026-11-20 to 2026-11-21 — PLAN-07, PLAN-21 and PLAN-08: started, landed, analysed, routed

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

## Decisions 2026-11-22 to 2026-11-23 — PLAN-09, PLAN-12, PLAN-23 and PLAN-10: landed, analysed, routed

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
- 2026-11-22 — PLAN-10 emitted (the operator confirmed the four-file overlap with the then running PLAN-12); the operator has not yet said
  "#10 is running", so it stays staged, not recorded as launched.
- 2026-11-22 — PLAN-12 landed (PR #54, `8d09629`) and was analysed on three layers: no blocker, **two major layer-3 defects** in
  `git-forms` (no nesting-depth limit: `validate`, `required` and `encode` end in a `StackOverflowError` on a document nested a few
  thousand levels, through four recursive sites behind an iterative reader; an unbounded decimal exponent: `1e2147483647` under a
  `multipleOf` schema stalls or throws in the library's `BigDecimal.remainder`, a gap the spec itself accepts), six minor (a duplicate
  collection accepted, unchecked prefix and paths, an unbounded file read, a validator rebuilt per call, YAML 1.1 words written plain, an
  expensive huge-integer parse), layer 2: five minor (codec key order ignores `$ref`/`allOf`, `format` silently not asserted, stale
  wording, missing tests, an automatic module never run on the module path), layer 1: the dependency record names no alternative
  although the spec asked for them. Not shown by reading: the build, the 1,362 tests, the Sonar read and the review numbers (both reads
  undecidable on a merged PR; the landing is incomplete at those four keys). Reported to the operator at once. **The operator's answer
  (2026-11-22):** a new PLAN-23 (`git-forms` hardening), emitted at once beside PLAN-10 and ahead of PLAN-13. The three candidate
  messages: 2 recurrences extended, 1 new lesson (`2026-11-22-21-004`); the run's own lessons `2026-11-22-21-001` to `-003` stay as filed.
- 2026-11-23 — PLAN-10 and PLAN-23 recorded launched, then running, on the operator's word ("both are running"; `auto_emit=false`).
- 2026-11-23 — PLAN-23 landed (PR #59, `bdbb5e4`) and was analysed on three layers: no blocker; layer 3: one major (the JSON Pointer string
  amplification of `DataNode`: an `OutOfMemoryError` from one small document under a map-of-maps schema, the mechanism confirmed by reading,
  the size not run) and five minor (the unbounded `holdsRequired` recursion along a `properties` → `$ref` chain, depth times the in-place chain,
  the hexadecimal reader/encoder digit drift, a shared-subtree hang, two aliases for one repository); layer 2: four minor (test gaps in
  `NestingDepthTest` and `NumberBoundsTest`, a doc limit not recorded); layer 1: the spec never says whether a hostile in-memory value is a
  result or an exception and never bounds depth times chain. The fixes of PLAN-12's two majors hold at the places traced (the reader, `DataNode`
  and `encode` bounds are consistent, the number bounds have no off-by-one, the kinds are bounded before they are read). The landing is
  incomplete (`effort` and the three review numbers); the review-gate re-read was decidable this time (0 unresolved, 1 body finding, `handled: 0`).
  Reported to the operator; routing pending. The session was restarted around this analysis (the anchor carried the state). The 14 candidate
  messages: 5 recurrences extended, 6 new lessons (`2026-11-23-12-001` to `-006`), 1 folded into the architecture Watch, 1 into PLAN-22, 1 discarded.

## Decisions 2026-11-23 to 2026-11-24 — PLAN-20, PLAN-10, PLAN-11 and PLAN-20 landed: analysed, dispositioned, routed

- 2026-11-23 — PLAN-20 released on the operator's word and its command emitted (not launched; `auto_emit=false`); the operator accepted the
  three-file overlap with PLAN-10 (`AGENTS.md`, `general.adoc`, `pom.xml`). PLAN-10 had in fact merged (PR #58, `ad9e773`) while its row read `running`.
- 2026-11-23 — PLAN-10 landed (PR #58, `ad9e773`) and was analysed on three layers (`landings/PLAN-10.md`): no blocker or major; layer 3: ten minor
  (an unbounded `allowed_signers` read, `Path.of` outside a try, a second `gpgsig` header stripped but not verified, a back-dated committer time
  inside the signed payload, an O(history) `RevWalk`, the `Z` suffix and the one-RSA-line refusal, the reference in two log records, an
  exception from `IdentityClaims`, an unisolated third-party `SigningKeySource`, a mislabelled exit message); layer 2: a test gap (merge commit,
  `mergetag`, second `gpgsig`, oversized file) and cross-checks that may only skip; layer 1: the `ssh.apache` premise is not shown (the own-encoder
  decision stands without it) and the scope cut "checkpoint and notes" was not flagged. The two persisted claim verdicts: claim 0 corroborated,
  claim 1 unverifiable. The landing is incomplete (`effort` and the three review numbers) and the review-gate re-read is undecidable
  (CodeRabbit "Minor comments: 20"). The 15 messages: 14 candidate lessons (11 new lessons `2026-11-23-14-001` to `-011`, with 013 folded into `-010`;
  007 folded into PLAN-11 as a Standing Constraint, no surface added; 012 folded into the shared-documents Watch) and the landing. Reported to the
  operator; routing of the layer-3 findings pending (candidate: PLAN-22 deliverable 4).
- 2026-11-24 — PLAN-11 and PLAN-13 confirmed `running` and PLAN-20 `launched` on the operator's word of 2026-11-23 ("all three are running";
  PLAN-11 and PLAN-13 observed through their plan directories, PLAN-20 not). By the morning PLAN-11 (PR #66, `b30ec14`) and PLAN-20 (PR #69,
  `f7eda4e`) had merged; PLAN-13's PR #64 was still open.
- 2026-11-24 — PLAN-11 landed (PR #66, `b30ec14`) and was analysed on three layers (`landings/PLAN-11.md`): no blocker; layer 3: **one major** (the
  anchor verifier never compares a checkpoint's parent with the previous checkpoint, so a checkpoint tag moved onto another anchored commit, or a
  duplicate subject, verifies clean; the "never moved" invariant of GDR-STORE-16 is undetected) and eleven minor (an unreadable checkpoint reported
  clean, the verifier ignoring `anchor.releases=false`, quadratic cost, unbounded reads of remote-controlled blobs, fetch updates lost when a due
  check throws, an `Instant.plus` overflow with a huge configured duration, the INFO proof record written before publication, an empty proof
  accepted, a previous proof taken unverified from the notes tip, a `Path.of` outside a try, no closed flag on `StoreClone.close`); layer 2: three
  minor (three in-code Sonar suppressions where the spec prescribes a fix or an operator-selected transition, raw threads in
  `AnchorInterleavingTest`, the mirrored set derived from `PUSH_REF_SPECS` but not tied to git-core's prune scope); layer 1: the SPI break
  `AnchorProvider.obtain(..., commitTime, ...)` the spec did not foresee, the "not re-requested" wording that holds only while the tip is
  unchanged, the Done-when "every GDR-STORE requirement is covered" that GDR-STORE-19 contradicts, and a stale surface premise (`git-store` was
  not a new module). The two persisted claim verdicts: both `corroborated`. The landing is incomplete (`effort` and the three review numbers);
  the review-gate re-read is undecidable (CodeRabbit "Minor comments: 1") and the Sonar read is undecidable (`head_revision_unavailable`).
- 2026-11-24 — PLAN-20 landed (PR #69, `f7eda4e`, after PRs #67 and #68 were closed for the CodeRabbit quota recovery) and was analysed on three
  layers (`landings/PLAN-20.md`): no blocker; layer 3: **one major** (`InCoreWriter.checkPaths` applies the strict Windows and macOS `ObjectChecker`
  to every edit, so a `Delete` of `aux.txt`, `trailing.` or `a:b` already stored in a parent tree is refused where it was possible before) and
  four minor (the duplicate and case-fold check ignores the parent tree and refuses a legitimate file-to-directory edit, `canonical()` treats every
  `FileSystemException` as missing, `storedOrigin` refuses a `//server/share` path, `isBranchName` refuses any `refs/` prefix beyond git);
  layer 2: four minor (no test pins the `HEAD` refusal, the rollback wiring is unobserved, the hermetic fixture is used by one test, `AGENTS.md`
  omits `READ_FILE_LIMIT_BYTES`); layer 1: the Done-when fail-first clause contradicts deliverable 3(c), the owner question for the bound of 4(b)
  is not shown. Persisted verdicts: claims 5, 11 and 13 `corroborated`, claim 22 `unverifiable`. The landing is incomplete (the same four keys);
  the review-gate re-read is decidable (0 unresolved, 13 body findings, `handled: 0`) and the Sonar read undecidable (`head_revision_unavailable`).
  Reported to the operator at once; the open defects of PLAN-03, PLAN-04, PLAN-05, PLAN-06, PLAN-18 and PLAN-19 routed to it are retired as far as
  the leaf traced them.
- 2026-11-24 — Message dispositions of the drain (33 messages: the landings 012 of PLAN-11 and 021 of PLAN-20, 11 and 20 candidate lessons).
  Twelve new lessons (`2026-11-24-07-001` to `-012`), twelve recurrences appended to existing lessons (`2026-11-23-14-009`, `-010`, `2026-11-23-12-002`,
  `2026-11-18-07-001`, `-002`, `-004`, `-006`, `-007`, `2026-11-18-13-005`, `2026-11-19-06-001`, `-005`, `2026-11-20-14-001`), one lesson repaired
  (`2026-11-23-14-011`: its title and body had carried the message's metadata lines, an error of the previous drain), two messages folded into the
  architecture-inventory Watch (the undeclared-empty `skills_by_profile` of `git-store`, again).
- 2026-11-24 — Emit round after the analysis: PLAN-13 was the only plan in flight. The operator answered the overlap prompts: PLAN-17 beside PLAN-13
  (4 to 5 shared files): emit; PLAN-22 released from its hold, and its two-file overlap with PLAN-13 and with PLAN-17: emit both; the layer-3
  findings of `git-store` (PLAN-10's ten minors and PLAN-11's major and eleven minors) folded into PLAN-22 as deliverable 4 (the fold added no file
  surface; two claims stamped through `corpus set-verdict`). PLAN-20's git-core findings were not folded: not routed. Nothing is recorded as launched.

## Decisions 2026-11-24 — the Audit Notary step-up request folded into PLAN-14, and the plan-marshall lessons moved

- 2026-11-24 — **Inbox drain: `audit-notary-001` (finding, sender `audit-notary`, an orchestrator of the Audit Notary repository) folded into PLAN-14 as
  deliverable 6.** The request: a boolean `step-up.enabled` (default `true`) that switches step-up off for every transition (and then requires none of `step-up.amr`, `step-up.acr`,
  `step-up.max-age`), and GDR-FORMS-7 stating that the deployment decides which `amr`/`acr` values satisfy step-up, `mfa` staying the default and a single method such as `pwd`
  allowed. Verified against ground truth: GDR-FORMS-7 at `7531b72` (`git-forms.adoc` 542-570) has no switch (per-transition `step-up` only; `step-up.max-age` required) and words
  `step-up.amr` as multi-factor only; the head of PR #64 (PLAN-13) adds `StepUpPolicy(amr, acr, maxAge)` with no `enabled` component and the same wording in its Javadoc, so the change lands on
  top of PLAN-13. Not verified: the consumer's own decision (ADR-0005 and AUN-AUTH-4/6 of cuioss/Audit-Notary were not read; the message is a lead on the consumer side, ground truth on
  the library side). Routing rule applied without asking: PLAN-13 is running (frozen), PLAN-14 is the next staged plan editing `git-forms/`, six deliverables. The fold adds file
  surface, so the Expected Surface gained `doc/concepts/identity-and-signing.adoc` and `doc/concepts/schema-driven-documents.adoc` (both word the setting as MFA) in the same edit; claim 2
  (the state at `7531b72`) is stamped `corroborated`. Not a plan-marshall subject, so the second standing rule does not apply. One thing the operator may want to decide: the request
  weakens the library's default reading of step-up (a deployment may now switch it off) — it follows the consumer's owner decision and keeps the default `true`.

- 2026-11-24 — **plan-marshall lessons and tooling findings moved** to the `lessons-routing` inbox of `/home/oliver/git/plan-marshall`, on the operator's word:
  76 lessons with a `plan-marshall:*` or a domain-bundle (`pm-*`) component became `candidate-lesson` messages (`libraries-initial-implementation-001` to
  `-074` for `plan-marshall:*`, `-076` and `-077` for the two `pm-dev-java` lessons; sender `libraries-initial-implementation`, one per lesson with its body,
  recurrences included) and were retired here (`remove`, verdict `superseded`, the tombstone names the message); the epic's tooling findings that are no
  lessons went as one `finding` (`-075`). The mapping is `lessons-moved.md`; lesson ids cited in this epic and in `landings/` for those components
  (`2026-11-18-*` to `2026-11-24-07-*`) resolve through it. The destination epic was open (phase `orchestrating`), its inbox read 121 live messages, 0 invalid,
  after the writes. Remaining local lessons: 8 `git-store`, 1 `git-core` (this repository's own modules).

## Decisions 2026-11-25 — the PLAN-13 and PLAN-17 landings analysed and reconciled, the ledger landed through PR #78

- 2026-11-25 — **PLAN-13 landing analysed and reconciled** (`landings/PLAN-13.md`; PR #64, `e38e079`; the inbox drain of the landing message and its eight candidate
  lessons). Row `shipped`, `pr` 64, `landing` stamped. The three-layer analysis (one read-only leaf, every `checked` point re-read except the middle of the depth-129
  chain) found one major and five minor layer-3 defects, two layer-2 test gaps and three layer-1 gaps. Routing under the standing rule and the operator's answers of
  2026-11-24: the forms-side findings, with the major L3-1 on "resume", into PLAN-14 deliverable 7 (seven deliverables, no new surface; the same edit added the claim
  about the state at `e38e079`); the `git-store` finding L3-2 into the new container plan PLAN-25 (held back; the pinned release's behaviour on the tag-only recovery
  path is decided at its outline because it changes GDR-STORE-6); L3-5 kept. The eight candidate lessons are plan-marshall subjects and went to the plan-marshall
  `lessons-routing` inbox as `libraries-initial-implementation-080` to `-087` (second standing rule; `lessons-moved.md`). Nothing was emitted, on the operator's word.

- 2026-11-25 — **PLAN-17 landing analysed and reconciled** (`landings/PLAN-17.md`; PR #74, `729ca28`; the inbox drain of the landing message and its eleven
  candidate lessons). Row `shipped`, `pr` 74, `landing` stamped. The three-layer analysis (one read-only leaf; the points that carry a finding re-read, the
  rest named as not re-read in the report) found no blocker and no major code defect, one major test gap (L2-1), six minor defects and one point not shown
  by reading (L3-7). Routing under the standing rule, without a question (no fork: nothing changes a documented behaviour before the owner decides it at
  an outline): no staged plan edits `git-timestamp`, so the new container plan **PLAN-26** (held back) takes L1-1, L2-1 to L2-3, L3-1 to L3-4, L3-6 and
  L3-7; the `git-store` finding L3-5 (the unbounded read of a stored note and proof) went into PLAN-25 deliverable 2 (refuted and dropped on 2026-11-25: PLAN-22 bounded every blob read). Documented-behaviour changes
  (the TLS trust statement, the `toString` host, the `tsa` check) are decided with the owner at PLAN-26's outline. The eleven candidate lessons are
  plan-marshall or `pm-*` subjects and went to the plan-marshall `lessons-routing` inbox as `libraries-initial-implementation-088` to `-098` (second
  standing rule; `lessons-moved.md`). Nothing was emitted, on the operator's word.
- 2026-11-25 — **Ledger landed through PR #78** (`3e3cfc4`, 36 paths: the PLAN-13 and PLAN-17 reconciliations, PLAN-25 and PLAN-26, the PLAN-14 deliverables 6 and 7,
  the drained inbox archive), on the operator's word ("analyze and land"); the worktree was resynced onto `3e3cfc4` with nothing to replay. The same PR
  brought `audit-notary-002` (filed by the consumer through an earlier land, PR #77) into the worktree.

## Decisions 2026-11-25 — the PLAN-22 landing analysed and reconciled

- 2026-11-25 — **PLAN-22 landing analysed and reconciled** (`landings/PLAN-22.md`; PR #76, `c22700a`; the inbox drain of the landing message and its 23 candidate
  lessons). Row `shipped`, `pr` 76, `landing` stamped. The three-layer analysis (one read-only leaf; the points that carry a major or minor finding re-read, the
  rest named as not re-read in the report) found two layer-3 majors, one accepted limit, ten minor defects and three layer-2 gaps. Routing under the standing rule,
  without a question (no fork: the owner decides each behaviour change at an outline): the store side of the tag-only recovery (L3-1) and the PLAN-22 leftovers
  (L3-4 to L3-7, L3-9, L3-10, L2-2, L2-3) into **PLAN-25** (deliverables 1 and 2, re-grounded at `c22700a`); the `git-forms` side (L3-2, L2-1) into **PLAN-14
  deliverable 7(h)**; the lost-lease classification (L3-3 and the `git-core` half of L3-4, an accepted owner decision of 2026-11-24 that the plan reopens with
  evidence) into **PLAN-24 deliverable 3**; PLAN-26 deliverable 2(e) narrowed. **PLAN-25's former deliverable 2** (the unbounded read of a stored proof, from
  PLAN-17's analysis) is **refuted and dropped**: PLAN-22 bounded every blob read at 16 MiB, re-read in `git-store` main. The 23 candidate lessons: 16
  plan-marshall subjects to the plan-marshall `lessons-routing` inbox (`-099` to `-114`), 6 rules of this repository's modules promoted into the local lessons
  store (`2026-11-25-08-001` to `-006`), 1 discarded onto the open item "empty `skills_by_profile`" (second standing rule; `lessons-moved.md`). Nothing was
  emitted, on the operator's word.

## Open Defects retired — PLAN-03, PLAN-19, PLAN-04, PLAN-06 and PLAN-12 (closed by PLAN-19, PLAN-20 and PLAN-23)

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
  — routed to PLAN-20 (deliverables 1 and 2) on the operator's word; PLAN-20 shipped (PR #69, `f7eda4e`) and its landing analysis traces both deliverables to code and tests (the rollback wiring is the one unobserved point, PLAN-20 L2-2); residue in the PLAN-20 entry below. Retired.
- PLAN-04 layer-3 defects in `git-core` (`landings/PLAN-04.md`, L3-1 to L3-9; one major, eight minor): the major one is the
  `dircache.InvalidPathException` from `DirCache.read` that escapes `InCoreWriter.write` and `compareAndSwap` instead of ending
  as a `FAILED` result (GDR-GEN-3); the minor ones are unchecked exceptions of a `Signer`, `GpgConfig` or `TreeOperation`, a retry
  loop that never ends for `attempts = Integer.MAX_VALUE`, a name conflict retried as a lost race, an unvalidated `refName`, a
  file and a directory below it in one edit silently merged, a platform-dependent path check, a query-string token or an
  uppercase-scheme credential kept in the clone's stored origin, and an unbounded `readFile`; with the layer-2 residues (untested
  `notWritten` branches, no reflog test, `ReferenceUpdater.parseCommit` peels a tag). PLAN-05 (PR #37) left all of them in place
  and added the same defect class to its new code (`landings/PLAN-05.md`, L3-1 to L3-8). — routed to PLAN-20 (deliverables 3 and 4)
  on the operator's word; PLAN-20 shipped (PR #69, `f7eda4e`) and its landing analysis traces the fixes to code (one major of its own, the
  refused `Delete` of L3-1 of `landings/PLAN-20.md`); residue in the PLAN-20 entry below. Retired.
- PLAN-06 layer-3 defects in the build guards (`landings/PLAN-06.md`, L3-1 to L3-4, with the layer-2 residue L2-1 to L2-3):
  `consumer-isolation` does not see a direct optional dependency, the layering rule is self-policing, the domain-term list is
  triplicated by hand, `NativeImageMetadataTest` checks names and not shape, the guards' negative runs live only in the squash
  message, and the native-image wording overclaims one class. — routed to PLAN-20 (deliverable 6); PLAN-20 shipped (PR #69, `f7eda4e`): the fourth check
  `direct-dependency-isolation`, the recorded negative runs (prose, not repeatable by a check) and the shape test are in; whether the new guard fires
  on a deliberately wrong optional dependency is not shown by reading. Retired.
- PLAN-12 defects in `git-forms` (`landings/PLAN-12.md`; two major, six minor, with the layer-1 and layer-2 residue): no nesting-depth
  limit (a `StackOverflowError` out of `validate`, `required` and `encode`), an unbounded decimal exponent under `multipleOf`, an
  expensive huge-integer parse, YAML 1.1 words written plain, a duplicate kind collection accepted, unchecked `numbering.prefix` and
  paths, an unbounded kinds-file read, a validator rebuilt per call; codec key order that ignores `$ref`/`allOf`, `format` silently not
  asserted, stale wording, missing tests, an automatic module never run on the module path, and a dependency decision recorded without its
  alternatives. — routed to PLAN-23 on the operator's word (2026-11-22); PLAN-23 shipped (PR #59): both majors are closed at the reader, the
  encoder and `DataNode` (a nesting limit of 128, number bounds of 1000), the minors are fixed or recorded, and the residue is the entry below.
  Closed.

## Watches retired — the leftovers of PLAN-18, the merge authorisation, the draft reference names, the per-attempt hook, the PLAN-22 rebase

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
- Plans after PLAN-08 assume `git-store`'s save exposes a per-attempt hook (regenerating output, PLAN-13; first-login
  checks, PLAN-16). If PLAN-08 lands without one, that is a store gap to stage, not to absorb in a forms or directory
  plan. — re-checked at the PLAN-08 landing (2026-11-21): the shipped `StoreWriter.save` has no per-attempt step, so the hypothesis of
  PLAN-13 and PLAN-16 was contradicted. A store gap, staged by the operator's word as PLAN-09 deliverable 5 (GDR-STORE-3 amended);
  PLAN-13 and PLAN-16 name it in their Dependencies. Retired.
- **PLAN-22 rebases over PLAN-13's pinned release** (2026-11-25): retired at PLAN-22's landing (2026-11-25). PLAN-22 merged on top of PLAN-13 and changed the same `DocumentRelease` and `StoreWriter`; the landing records no conflict, and the analysis read `DocumentRelease` at `c22700a` with the pin and the three-argument release intact (`landings/PLAN-22.md`). PLAN-25 follows on the same files.

