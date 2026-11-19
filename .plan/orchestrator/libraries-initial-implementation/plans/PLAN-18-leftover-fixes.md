# PLAN-18: Collected leftover fixes (git-testing first)

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Collect the minor leftovers the three-layer analyses of landed plans find, and fix them in one plan instead of one
plan per finding. It starts with the leftovers of PLAN-01 (`git-testing`): four missing test assertions, a cleanup that
depends on a JUnit setting, an `@InMemory` repository that differs from the bare fixtures, and a module descriptor
that no test runs on the module path. Later analyses fold further leftovers into this spec (see Dependencies and
Sequencing) until the operator releases it; none of them gives a wrong result on the default path.

## Deliverables

1. Test gaps of PLAN-01, each closed by an assertion that fails when the property breaks: (a) the in-memory fixture
   does not leak its DFS type — a test fails when an `InMemoryRepository`-typed parameter is accepted or the declared
   type of the provided value is not `Repository`; (b) the repositories are closed when the test ends — asserted for
   the bare fixture (including the rollback test, whose title already claims it) and for the in-memory fixture;
   (c) `GitCredentialGenerator` produces random values — two successive credentials differ in user and token;
   (d) the cleanup assertion over `RECEIVED_DIRECTORIES` in `LocalRepositoryExtensionTest` guards against an empty
   population without failing a single-test run (lesson 009 of the PLAN-01 retrospective: assert over what was
   recorded, and keep the non-vacuity check where it cannot fail a subset of the tests).
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
2. Cleanup that does not depend on a JUnit setting: the closeable resources of the three resolvers are stored so that
   Jupiter closes them whether or not `junit.jupiter.extensions.store.close.autocloseable.enabled` is true, and a test
   runs the fixtures with that setting off and asserts that the directories are gone and the repositories closed.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
3. `@InMemory` parity with the bare fixtures: either the in-memory repository gets the same initial state as the
   bare ones (HEAD linked to the initial branch), or the difference is documented in the Javadoc of `InMemory` and in
   `doc/specification/git-testing.adoc` with the reason. The choice follows GDR-TEST-1 and is recorded in the plan's
   decision log; a behaviour change is covered by a test of HEAD and of the initial branch name.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc, pm-documents:ref-asciidoc
   - Persona: implementer
4. The module descriptor on the module path: a test or a dedicated Surefire execution runs `git-testing`'s exports and
   `requires transitive` on the module path, or the limit (JGit and `cui-test-generator` are automatic modules, and the
   class-path workaround of `git-testing/pom.xml` hides the descriptor from every test) is recorded in
   `doc/specification/git-testing.adoc` with what a consumer module such as `git-core` has to do. The existing
   `useModulePath=false` stays unless the new run replaces its reason.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: implementer
5. Leads the analysis could not settle by reading, each either tested or fixed, or recorded as an accepted exception
   with its reason in `doc/specification/git-testing.adoc`: (a) the fixtures under `@TestInstance(PER_CLASS)` (the
   `TEST_METHOD` scope is tested only with the default per-method lifecycle); (b) cleanup of a tree that holds a
   dangling symbolic link (`FileUtils.delete(file, SKIP_MISSING)` may skip it and the directory delete then fails);
   (c) `GitCredentialGeneratorTest`, `FailingListingFileSystem` and the `SystemReader.Delegate` subclasses use hand-written
   doubles where AGENTS.md prescribes EasyMock — either EasyMock replaces them where it can, or the exception is
   recorded with the reason.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS, `@NullMarked` packages, Javadoc on every public type and method, license header
  via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock. Build only through the canonical commands
  in `CLAUDE.md` (`python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`).
- No dependency without the owner's approval (AGENTS.md); the approved list is JGit 7.8.0, `cui-test-generator`,
  JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok and `cui-java-tools`. `git-testing` depends on no reactor module.
- Verify claims about JGit APIs against its documentation or source and name the JGit version checked (CLAUDE.md).
- The public behaviour of the three fixtures and the generator stays as PLAN-01 shipped it, except where deliverable 3
  decides otherwise and records it.
- Do not touch `.github/project.yml` (release guard, AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker. The merge gate reads the open-finding
  count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks.

## Done when

- `verify` passes on the reactor at the plan's head, and each assertion of deliverables 1 and 2 fails when the property
  it guards is broken (shown by a recorded mutation of the code under test, or by the assertion's construction).
- Every item of deliverables 3 to 5 is either fixed and tested at the plan's head, or recorded as an accepted exception
  with its reason in `doc/specification/git-testing.adoc`.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review) is `0` or every key
  is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: no test asserts that an `InMemoryRepository`-typed parameter is rejected; the only wrong-type test uses an
  `@InMemory String` — read at `git-testing/src/test/java/de/cuioss/git/document/testing/InMemoryRepositoryExtensionTest.java`
  § `shouldRejectAnnotatedParameterOfAnotherType` at merge commit `6cc64f0`.
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: OBSERVED claim re-read at base 55b0f89 by the three-layer read and true at PLAN-01's head (PR #28 analysis)
- OBSERVED: the rollback test is titled "Should close the repository and delete its directory" but asserts only the
  cause type and the absence of the directory — read at
  `git-testing/src/test/java/de/cuioss/git/document/testing/LocalRepositoryExtensionTest.java` § `shouldRollBackOnUncheckedFailureOfCreation`.
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: OBSERVED claim re-read at base 55b0f89 by the three-layer read and true at PLAN-01's head (PR #28 analysis)
- OBSERVED: `GitCredentialGeneratorTest` contains no assertion that two successive credentials differ — read at
  `git-testing/src/test/java/de/cuioss/git/document/testing/GitCredentialGeneratorTest.java` (no `assertNotEquals`
  and no reference to randomness).
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: OBSERVED claim re-read at base 55b0f89 by the three-layer read and true at PLAN-01's head (PR #28 analysis)
- OBSERVED: `LocalRepositoryExtensionTest` asserts over `RECEIVED_DIRECTORIES` in an `@AfterAll` without a non-vacuity
  guard, while `LocalRemoteExtensionTest` and `CombinedFixturesTest` guard — read at
  `git-testing/src/test/java/de/cuioss/git/document/testing/LocalRepositoryExtensionTest.java` § `everyDirectoryIsDeleted`.
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: OBSERVED claim re-read at base 55b0f89 by the three-layer read and true at PLAN-01's head (PR #28 analysis)
- OBSERVED: `TemporaryRepository` and the in-memory `Repository` are stored in the extension context store as
  `AutoCloseable`, not as `Store.CloseableResource` — read at
  `git-testing/src/main/java/de/cuioss/git/document/testing/LocalRepositoryExtension.java` § `TemporaryRepository`
  and `git-testing/src/main/java/de/cuioss/git/document/testing/InMemoryRepositoryExtension.java` § `resolveParameter`.
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: OBSERVED claim re-read at base 55b0f89 by the three-layer read and true at PLAN-01's head (PR #28 analysis)
- HYPOTHESIS: Jupiter closes a stored `AutoCloseable` only while `junit.jupiter.extensions.store.close.autocloseable.enabled`
  is true, and always closes a `Store.CloseableResource` — confirm/refute at the `junit-jupiter-engine` 6.1.3 class
  `DefaultJupiterConfiguration` § `isClosingStoredAutoCloseablesEnabled` (verify-at-outline; JUnit version named).
  - verdict: unverifiable | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: Jupiter's auto-close semantics (junit-jupiter-engine 6.1.3) not settled by reading; the design relies on it and the second Surefire run exercises it
- HYPOTHESIS: `InMemoryRepositoryExtension` never calls a `create` that links HEAD, so `@InMemory` has no HEAD while the
  bare fixtures do — confirm/refute at `git-testing/src/main/java/de/cuioss/git/document/testing/InMemoryRepositoryExtension.java`
  § `resolveParameter` and JGit 7.8.0 `org.eclipse.jgit.internal.storage.dfs.DfsRepository` § `create`
  (verify-at-outline).
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: JGit 7.8.0 DfsRepository#create links HEAD to refs/heads/master; the pre-change fixture never called create
- HYPOTHESIS: JGit and `cui-test-generator` 3.0.2 are automatic modules, so `requires transitive` on them gives a
  javac lint and the descriptor behaves differently on the module path — confirm/refute at the jar manifests of both
  artifacts and `git-testing/src/main/java/module-info.java` (verify-at-outline).
  - verdict: corroborated | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: both jar manifests carry Automatic-Module-Name and neither jar holds module-info.class; the javac-lint half is not shown by reading
- HYPOTHESIS: `FileUtils.delete(file, SKIP_MISSING)` returns early for a dangling symbolic link, so a directory
  holding one is not deleted — confirm/refute at JGit 7.8.0 `org.eclipse.jgit.util.FileUtils` § `delete(File, int)`
  and `org.eclipse.jgit.util.FS` § `exists` (verify-at-outline).
  - verdict: contradicted | checked_at: 7418f764f5aee43f4aed3a0d79bfa779b5b790f9 | by: libraries-initial-implementation/analyze | rescoped: no | evidence: FileUtils.delete skips only when !fs.exists(f); FS.exists delegates to FileUtils.exists (Files.exists NOFOLLOW_LINKS, JGit 7.8.0), so a dangling link is deleted; no code change was needed
- Verify-first clause: the three-layer analysis of PLAN-01 read the code only (no build, no test run); every
  OBSERVED claim above is re-read at the plan's own HEAD at outline, and a refuted one is dropped from the deliverable
  that names it.

## Expected Surface

- OBSERVED: `git-testing/src/main/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-testing/src/test/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-testing/pom.xml`
- OBSERVED: `doc/specification/git-testing.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-01 (landed as PR #23, merge commit `6cc64f0`).
- Collection plan: **hold it back from `next` until the operator releases it.** `analyze` folds further leftovers
  into this spec as new deliverables or as items of deliverable 5, in the same edit as their Expected Surface; the
  operator releases the plan by naming it in a `next` round. It is queued at the end of WS-01 and has no technical
  dependency on any other staged plan.
- Overlaps with: none of the staged plans (PLAN-02..PLAN-17 do not declare any `git-testing/` path); a leftover folded
  in later that touches `AGENTS.md`, a `pom.xml` or `doc/specification/general.adoc` overlaps with the plans that
  declare those files.
- Adjacent to: `git-core/` and `git-worktrees/` — untouched here; `git-testing` depends on no reactor module.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-18-leftover-fixes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
