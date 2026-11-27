# PLAN-29: git-forms — the two majors, the minors and the test gaps of PLAN-14's landing

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fix the defects, the test gaps and the specification wording that the three-layer analysis of PLAN-14 (`landings/PLAN-14.md`, PR #81, `0187cf3`) found in `git-forms`, and settle by a test
the four points that reading could not settle. This is the container plan of the epic for `git-forms` after PLAN-14: it was staged on 2026-11-28 on the operator's word ("stage plan-14 defects";
a layer-3 defect becomes a spec only on the operator's word) because no staged plan fits by content (PLAN-27 stages within a document and its fold was declined once). The two majors are in the
resume of a failed release and in the allocation key of a numbered entry whose first transition is a release. **Held back from `next` until the operator releases it**; it runs after PLAN-28
(which changes the identity code in the same transition classes) and before PLAN-27. Later `git-forms` leftovers of landing analyses fold into this spec until it is launched.

## Deliverables

1. The two majors (`landings/PLAN-14.md` L3-1, L3-2), each reproduced by a test that fails at the plan's base commit (lines are those of `0187cf3`; PLAN-28 moves some of them, re-read at the outline):
   (a) **L3-1, a failed release is never released after a narrative-only revision**: `TransitionContext.unreleasedTip` (197-202) reports a release complete when the saved file is byte for byte the file at ANY
   release tag of the key; after `approved -> draft -> review -> approved` with the data unchanged, a failed release of version 2 leaves `document.yaml` equal to the tag of version 1, so the retry is
   `TRANSITION_REFUSED` and version 2 is never released (the same after `SAVED_NOT_RELEASED`). Decide at the outline, with the owner, what "complete" means: the recommendation is to compare commits,
   not files (a release tag whose commit has the saved tip as an ancestor or as its second parent; `RepositoryReader` of `git-core` and `CloneReader.releaseTagTargets` have what it needs), and to record the rule
   in `git-forms.adoc` GDR-FORMS-5. Tests: the sequence above ends released with the next version, the case with the data changed (unchanged), the complete release (still refused), and a release of version 1
   whose tag is missing.
   (b) **L3-2, a release-first numbered entry loses its allocated key when the release fails**: `TransitionSave` (124-127, 152-161) builds the key from `step.allocated()` and releases under it, but a failed
   release returns the passed-on result without the key (154-156), the ULID draft file is already deleted, and a retry under the ULID (`TransitionRunner` 104-135) reads the missing file as the initial state:
   `MANDATORY_FIELDS_MISSING`, or, if the caller resends all data, a second save takes the next number (a duplicate entry and an unreleased orphan). The default workflow (the first transition is a save) is
   unaffected. Decide at the outline: the result of a failed release carries the allocated key so that the caller retries under it (the recommendation; `Result.NotPerformed` gains the key, and the specification
   says so), or the number is allocated only when the release is certain. Tests: a custom workflow whose first transition is a release, a failed release, a retry under the reported key that ends released
   with that number, and no second number allocated.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: implementer
2. The minor defects, each first re-read at the plan's head and reproduced by a test that fails at the plan's base commit: (a) **L3-4**: `Deadlines` (311-313, 331-352) turns a `DateTimeException` or an
   `ArithmeticException` into "no deadline", so a `review-interval P1D` at an `at` beyond about year 5.9 million, or `at = Instant.MAX` used as "list all", omits a document whose first occurrence is due: return
   the due instant or a result, never silence; (b) **L3-5**: one undecodable `document.yaml` ends `due` (173-177) with `MALFORMED` for the whole listing: decide with the owner between that (spec-conformant; say
   so in GDR-FORMS-6) and a result that carries the due deadlines and names the documents it could not read; (c) **L3-7**: `TransitionContext.resumable` (166-169) checks the name, the `release` mechanism and
   `to == current` but not that the state came from this transition's `from`, so calling `approve` (from `review`) releases a document that was never in review where a custom workflow reaches `approved` by a save:
   guard it or state in GDR-FORMS-5 why not; (d) **L3-8**: `KindConfiguration.physical` (262-265) compares the remote as registered, so two aliases for one repository spelled differently (a trailing `.git`,
   case, scheme) pass the duplicate-collection check and share a branch namespace: normalise the comparison or state the limit; (e) **L3-9**: the resume path fetches (`TransitionRunner` 114) before the
   verification check (131), so a caller who may not release learns, from the outcome that differs from `TRANSITION_REFUSED`, that a release is incomplete: verify first, or record why not. (The layer-3 minor
   L3-6, an enabled step-up policy that can never hold, is removed with `StepUpPolicy` by PLAN-28 and is not an item here.)
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: implementer
3. The four points reading could not settle (`landings/PLAN-14.md` L3-3, L3-10, L3-11, L3-12), each by a test or a measurement recorded in the PR, and a defect found is fixed in the same deliverable (or, if it
   needs a decision, reported and not absorbed): (a) **L3-3**, whether the lazy pointer of `DataNode` (288-306) fixes the memory of a validation under a map-of-maps schema end to end: a test that validates a document
   of the size the item names and bounds the allocation (`org.openjdk.jol:jol-core` is already a test dependency of `git-store` only; no new dependency without the owner's approval); (b) **L3-10**, whether
   `validate` overflows the default stack at 128 data levels under a chain of 127 in-place references (`NestingDepthTest` 312 covers `required` only); (c) **L3-11**, whether the history walk of `Deadlines`
   (243-269) places the entering instant wrongly under clock skew between writers, since `RepositoryReader.history` orders by commit time, and what the walk costs per due document; (d) **L3-12**, a
   `CatalogResolver` that throws on a CHANGED save ends the transition as `FAILED` naming only the class (only the unchanged-save path is tested, `TypedLinkCheckTest` 288).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
4. The test gaps and the wording (`landings/PLAN-14.md` L2-1 to L2-8, L1-2, L1-4), each re-read at the plan's head: (a) **L2-1** `TypedLinkCheckTest` (93-98): a `gdr-link` under `anyOf`/`oneOf`, in `items`, under
   `$dynamicRef` and in a split part, and a retired catalog entry through a resolver that says retired (not a stub that returns true); (b) **L2-2** `ConcurrentNumberingTest` (114-167): a scripted loser that
   re-reads the counter on its re-applied attempt, with the retry loop no longer hiding `REJECTED`; (c) **L2-3** `DataNodeTest` (234-273): validate under a map-of-maps schema; (d) **L2-4** `NestingDepthTest`
   (302-315): `validate` at depth 128 under a chain of 127; (e) **L2-5** `KindConfigurationTest` (701-745): two aliases with differently spelled remotes (with item 2(d)); (f) **L2-6**
   `SchemaValidatorPreparationTest` (86-106): a guard that notices a re-preparation inside `validate` or `required`; (g) **L2-7** `DocumentTransitionsTest.fragmentOverTheBlobLimit`: assert the passed-on `FAILED`,
   not any `NotPerformed`; (h) **L2-8** state in the PR why `doc/concepts/git-as-document-persistence.adoc`, the removed `DocumentLayout.isOutput` and the `StoreIndex` change were part of PLAN-14 (the
   narrative named only three outliers); (i) **L1-2, L1-4** `git-forms.adoc` GDR-FORMS-6 says that the entering instant comes from the history of the state, not from the index, which cannot hold it. Keep
   `FormsOutcomeSpecificationTest` and every list-in-sync test green in both directions after any outcome change.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing, pm-documents:ref-asciidoc
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked` packages with `@Nullable` where needed, results instead of exceptions for
  expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and
  method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail, exception message, log message or commit metadata.
- Every behaviour fix is first reproduced by a test that fails at the plan's base commit and passes at the plan's head; the base commit is named at the outline, after PLAN-28 has shipped. An item whose
  premise the re-read at outline refutes is dropped from its deliverable and recorded.
- `git-store`, `git-core`, `git-timestamp` and `git-testing` stay read-only; an item that needs a change there is reported in the PR and the inbox message, not absorbed (PLAN-14 lifted this once for one accessor
  at the operator's decision).
- Verify every claim about a JGit API against JGit 7.8.1 source or documentation and name the version (CLAUDE.md).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md). Do not touch `.github/project.yml` or any workflow file (every PR that touches the first starts the release workflow; AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S SELECTION — the question tool names each finding key, the progress record
  records the selection as `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is
  a blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks. The three PLAN-14 suppressions (`LinkTargets` S4968,
  `TransitionStep` S107, `DataNode` S1643) carry no `operator-selected` record: put them to the operator if a change here touches them.
- Review lessons of PLAN-14 (`2026-11-27-22-001` to `-007` of the local lessons store): a consumer-supplied callback is isolated at EVERY call site, with a test per call site (changed save, unchanged
  save, resumed release); a test that keeps code and specification in step checks both directions; read the value of a result only after `isOk()`; quote the outcome the isolation code actually returns, never
  a paraphrase; an explicit work stack filters at push time; sync with `origin/main` before every push of a fix round.

## Done when

- `verify` passes at the plan's head.
- The behaviour fixes of deliverables 1 and 2 each have a test that fails at the plan's base commit (named at the outline; the defects are present at `0187cf3`, `main` after PLAN-14, by the reading of
  `landings/PLAN-14.md`) and passes at the plan's head; an item the outline decides to document instead of to fix has the sentence in `git-forms.adoc` and a test that pins the documented behaviour.
- Each of the four points of deliverable 3 ends as a passing test or a measurement in the PR, or as a defect fixed under deliverable 1 or 2, or as a reported finding with the decision it needs.
- The gaps of deliverable 4 are closed by tests that can fail: each new test fails when the behaviour it names is broken (shown at the outline by the mutation the PR names).
- `FormsOutcomeSpecificationTest` and every other list-in-sync test hold in both directions, and the requirement numbers of `git-forms.adoc` stay without a gap.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: a release is read as complete from the bytes of the document file alone — `git-forms/src/main/java/de/cuioss/git/document/forms/TransitionContext.java` 197-202 loops over `clone.releaseTagTargets(key)`
  and returns empty when `Arrays.equals(released.get(), saved.get())` for any tag target; the state is part of the file, so a returning state with unchanged data matches, at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: TransitionContext.java 197-202 returns empty on Arrays.equals(released.get(), saved.get()) for any release tag target
- OBSERVED: the allocated key is dropped on a failed release — `git-forms/src/main/java/de/cuioss/git/document/forms/TransitionSave.java` 124-126 builds the key from `step.allocated()`, 152-156 returns
  `TransitionOutcomes.passOn(released)` without it, and `TransitionRunner.java` 100-104 reads the state of the key the caller passed (the ULID), at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: TransitionSave.java 124-126 builds the key from step.allocated(), 152-156 returns passOn(released) without it; TransitionRunner.java 100-104 reads the state of the caller's key
- OBSERVED: a Period rule at an extreme instant is dropped — `git-forms/src/main/java/de/cuioss/git/document/forms/Deadlines.java` 311-313 returns `Optional.empty()` on a `DateTimeException` or an
  `ArithmeticException`, and `plus` (349-352) calls `Math.toIntExact(times)` while the bisection (331-336) doubles `high`, at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: Deadlines.java 311-313 returns empty on DateTimeException or ArithmeticException; plus 349-352 uses Math.toIntExact
- OBSERVED: one unreadable file ends the whole listing — `Deadlines.java` 173-177 returns the first non-ok result of `due(...)` for any document, at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: Deadlines.java 173-177 returns the first non-ok found
- OBSERVED: the resume rule does not check the origin state — `TransitionContext.java` 166-169 filters the transition by name, `Mechanism.RELEASE` and `to().equals(state)` only, at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: TransitionContext.java 166-169 filters by name, Mechanism.RELEASE and to().equals(state)
- OBSERVED: the alias identity is the remote as registered — `git-forms/src/main/java/de/cuioss/git/document/forms/KindConfiguration.java` 257-265 (`physical` returns `repositories.find(alias)...remote()` and
  its Javadoc says the remote is compared as registered), at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: KindConfiguration.java 257-265 physical() returns the remote as registered, its Javadoc says compared as registered
- OBSERVED: the resume path fetches before the check — `TransitionRunner.java` 114 (`writer.fetch()`) precedes 131 (`policy.requires(allowed) && !policy.holds(...)`), at `0187cf3`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: TransitionRunner.java 114 writer.fetch() precedes 131 policy check
- HYPOTHESIS: the lazy pointer of `DataNode` bounds a map-of-maps validation end to end — confirm or refute at `git-forms/src/main/java/de/cuioss/git/document/forms/DataNode.java` § `getJsonPointer` (288-306)
  by a validation of the size the item names (verify-at-outline).
- HYPOTHESIS: `validate` at data depth 128 under a 127-schema in-place chain overflows the default stack — confirm or refute at `git-forms/src/test/java/de/cuioss/git/document/forms/NestingDepthTest.java` §
  `depthTimesChain` (302-315) extended to `SchemaValidator.validate` (verify-at-outline).
- HYPOTHESIS: the history walk misplaces the entering instant under clock skew because `RepositoryReader.history` orders by commit time — confirm or refute at
  `git-forms/src/main/java/de/cuioss/git/document/forms/Deadlines.java` § `entered` (243-269) and `git-core/src/main/java/de/cuioss/git/document/core/RepositoryReader.java` § `history` (verify-at-outline).
- HYPOTHESIS: a throwing `CatalogResolver` on a changed save ends as `FAILED` naming only the class — confirm or refute at `git-forms/src/main/java/de/cuioss/git/document/forms/CatalogResolver.java` §
  `resolve` and the step isolation of `git-store/src/main/java/de/cuioss/git/document/store/StoreEvents.java` 222-227 (verify-at-outline).
- HYPOTHESIS: the test gaps L2-1 to L2-7 exist as the leaf of the PLAN-14 analysis read them (it read, the orchestrator did not re-read them) — confirm or refute at the named tests:
  `TypedLinkCheckTest.java` 93-98, `ConcurrentNumberingTest.java` 114-167, `DataNodeTest.java` 234-273, `NestingDepthTest.java` 302-315, `KindConfigurationTest.java` 701-745,
  `SchemaValidatorPreparationTest.java` 86-106, `DocumentTransitionsTest.java` § `fragmentOverTheBlobLimit` (verify-at-outline).
- Verify-first clause: at the outline re-read every OBSERVED line at the plan's head (PLAN-28 changes `TransitionRunner` 131 and the identity classes); a refuted claim loops back to re-scope.

## Expected Surface

- OBSERVED: `git-forms/`
- OBSERVED: `doc/specification/git-forms.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (only if an outcome or its detail changes)
- HYPOTHESIS: `AGENTS.md` (only if the "Current state" statement of `git-forms` changes)

## Dependencies and Sequencing

- Depends on: PLAN-14 (shipped, PR #81, `0187cf3`), whose landing analysis is this plan's source, and PLAN-28 (staged 2026-11-27, the next plan to emit): it changes `TransitionRunner` 131 and the identity
  classes of the same package, supersedes `StepUpPolicy` (so L3-6 is not an item) and renames the outcome and the workflow key; re-ground this spec's lines at the outline.
- **Held back from `next` until the operator releases it**; runs BEFORE PLAN-27 (the stages build on the resume and the allocation fixed here); strictly sequential per document with the other staged
  plans (`AGENTS.md`, `general.adoc`, `git-forms.adoc`).
- Overlaps with: PLAN-27 (`git-forms/`, the transition code and `git-forms.adoc`) and PLAN-28 (the same classes). PLAN-25 edits `git-store/` only.
- Adjacent to: `git-store`, which stays read-only (the release path is PLAN-25's).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-29-forms-hardening-2.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
