# PLAN-20: Collected leftover fixes (git-worktrees, git-core, git-testing)

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Collect the leftovers the three-layer analyses of landed plans find — the layer-3 defects of PLAN-19 (`git-worktrees`)
and PLAN-04 (`git-core`), the test gaps beside them, and the `git-testing` leftovers of PLAN-18 that have no other
spec — and fix them in one plan instead of one plan per landing. This is the second collection plan, after PLAN-18.
None of the items gives a wrong result on the default path: each needs a malformed configuration, a hostile or
unusual input, or a failure in the middle of an operation. Later analyses fold further leftovers into this spec (see
Dependencies and Sequencing) until the operator releases it.

## Deliverables

1. `git-worktrees` result contract (leftovers of PLAN-19's analysis, `landings/PLAN-19.md` L3-1 to L3-3 and L3-6): each
   item is first reproduced by a test that fails at the base, then fixed. (a) `commit`, `worktreeStatus` and
   `worktreeRemove` end as a `FAILED` result with the class name only when JGit throws `IllegalArgumentException`, for
   example with `core.autocrlf = bogus` in the repository configuration; the reach is checked for every operation that
   builds a `FileTreeIterator` or reads `WorkingTreeOptions`, not only for the three named; (b) a failure while the
   add rollback releases its target (`UncheckedIOException` from the directory stream) does not skip the rest of the
   rollback: the created branch is deleted and the reservation released; (c) a failed release of the branch
   reservation on the success path does not tear down a finished worktree — the outcome the caller sees is decided
   and recorded in the plan's decision log, and tested; (d) the reflog step of `commit` catches every failure it can
   raise, so a stored commit is not reported as thrown.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
2. `git-worktrees` paths, names and documentation (PLAN-19 analysis L3-4, L3-5, L3-7, L3-8 and L1-2): (a) `realPath`
   does not test existence and then resolve (the removal of a registered tree between the two calls ends
   `worktreeRemove` of an unrelated tree) and resolves a symlinked ancestor of a missing path, so a prunable tree
   removed through an alias is found; (b) the branch-name guard either applies git's rules for the names JGit 7.8.0
   accepts and git refuses or reads differently (`HEAD`, a leading `-`, a component ending `.lock` inside the name, `@`,
   the full `refs/heads/x` form), or the limit is recorded in `doc/specification/git-worktrees.adoc` with its reason
   — the choice follows what git itself accepts, which is checked against `git check-ref-format` in the compatibility
   test; (c) `worktreeAdd` on a bare root, which since PLAN-19 always ends `PATH_REFUSED` ("inside the git directory")
   while `git worktree add` works there: supported with a test, or the refusal documented in the Javadoc and the
   specification with a test that pins it; (d) the two stale Javadoc sentences (`WorktreeOperations` § `worktreeAdd`
   "until the new working tree is registered" and the `path` parameter, which omits the refusals for the git directory
   and a linked root) and the two outcome lists of the specification that are still short.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc, pm-documents:ref-asciidoc
   - Persona: implementer
3. `git-core` write path (leftovers of PLAN-04's analysis, `landings/PLAN-04.md` L3-1 to L3-7, L2-4): each item is first
   reproduced by a test that fails at the base. (a) `InCoreWriter.write` ends `FAILED` (class name only) when the
   parent tree holds an entry JGit's path check rejects — an entry named `.GIT` or `git~1` that arrived through a push
   without fsck — instead of letting `dircache.InvalidPathException` escape from `write` and `compareAndSwap`; the
   test builds such a tree with an `ObjectInserter`; (b) the unchecked exceptions of caller code and configuration
   (`Signer`, `gpg.format` through `GpgConfig`, `TreeOperation.apply`, a `null` result or element) are either returned
   as `FAILED` with the class name or stated in the Javadoc as the caller's contract, decided and recorded, with a
   test; (c) the retry loops of `compareAndSwap` and `compareAndDelete` cannot overflow for `attempts =
   Integer.MAX_VALUE`, or the bound is validated and documented; the test terminates at the base too (a repository
   double ends the loop after a fixed number of calls and the assertion names the number); (d) `compareAndSwap` tells
   a name conflict (`refs/heads/a/b` while `refs/heads/a` exists) from contention, running the operation once and not
   on every attempt, and a `refName` that is not a valid reference name (`refs/heads/../../../outside/x`) is refused
   before any file is touched, with the outcome `git-worktrees` uses for an invalid name (`REFUSED_BY_POLICY`, no new
   constant); (e) edits that overlap as a file and a directory below it (`Put("a")` with `Put("a/b")`) are refused like a
   repeated path, or the loss is documented with a test; (f) `ReferenceUpdater.parseCommit` resolves with `parseAny`
   and checks the type, so a reference that points at an annotated tag is a result, not a silent peel; (g) the
   platform dependence of the path check (`SystemReader.checkPath`: Windows names and a trailing dot accepted on
   Linux, case-fold duplicates, an installed `SystemReader`) is put to the owner through the question tool — one
   strictness on every host, or the host's rules, documented — and the choice is recorded in `git-core.adoc`.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc, pm-dev-java-cui:cui-logging
   - Persona: implementer
4. `git-core` repository operations and test residue (PLAN-04 analysis L3-8, L3-9, L2-1, L2-2, L2-5, L2-7): (a) the
   stored origin of a clone carries no secret the caller passed: a token in the query string and the credentials of an
   uppercase-scheme URL (which `URIish` parses as a local path) are not written to the clone's configuration, and the
   test reads the configuration file text; (b) `readFile` bounds the size of a blob it reads into memory, or the
   accepted exception in `git-core.adoc` gets a reason that holds for a policy that admits a whole host — put to the
   owner through the question tool, because a bound is a compiled limit no requirement asks for; (c) test gaps, each
   closed by an assertion that fails when the property breaks: the `REJECTED` and the `IO_FAILURE`/default branches of
   `notWritten` (and a `null` result of a `TrackingRefUpdate`, if JGit 7.8.0 can leave one); no reflog is written by
   `compareAndSwap` and `compareAndDelete`; `neverForces` fails when the force flag is set (observed through a
   repository double), not only when the expected id is dropped; the generated names of `InCoreWriterTest` cannot
   collide in a `Map.of`.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
5. `git-testing` leftovers of PLAN-18's landing (`landings/PLAN-18.md` L1-5, L1-6, L2-1 to L2-5, L3-1, L3-2), each
   re-read against the code at the plan's head first because PR #29 rewrote two extension tests since the analysis,
   then fixed or tested, or recorded as an accepted exception with its reason in `doc/specification/git-testing.adoc`:
   the positive half of the `@InMemory` type assertion is tautological; the rollback wiring from `createBareRepository` to
   `rollBack` is unobserved; nothing asserts that the "auto-close off" Surefire run received its setting; `TemporaryRepository.close`
   loses the first failure when closing and deletion both fail; the public Javadoc of the three extensions is stale
   under `PER_CLASS`; `GitCredentialGeneratorTest` never held a double (a premise to drop if the read confirms it).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:javadoc
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS, `@NullMarked` packages with `@Nullable` where needed, results instead of exceptions
  for expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a documented log-message
  catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and method, license header
  via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the `git-testing` fixtures and
  without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result
  detail, exception message, log message, stored configuration or commit metadata.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Every behaviour fix is first reproduced by a test that fails at the base and passes at the head; an item whose
  premise the re-read at outline refutes is dropped from the deliverable that names it.
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call
  `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit 7.8.0, `cui-test-generator`, JUnit 5,
  EasyMock, `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through the
  question tool BEFORE it is added.
- No new `GitOutcome` constant without the owner's approval: an invalid name reuses `REFUSED_BY_POLICY` as PLAN-19 did.
  A changed outcome of a public operation is updated in the Javadoc, the specification table, `general.adoc` and
  `doc/clients/pm-mcp-core.adoc` in the same commit.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- When a public type or method changes, update the "Current state" paragraph of `AGENTS.md` and the specification
  document of its module.
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is a
  blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected;
  an `undecidable` read blocks.

## Done when

- `verify` passes on the reactor at the plan's head, and every behaviour fix of deliverables 1 to 4 has a test that
  fails at `c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c` (`main` after PLAN-04, where every defect below is present) and
  passes at the plan's head.
- Every item of deliverables 1 to 5 is either fixed and tested at the plan's head, or recorded as an accepted exception
  or an owner decision with its reason in the specification document of its module; no document lists an outcome the
  code does not return.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `IllegalArgumentException` is caught by `worktreeAdd` and `worktreeList` but not by `commit`, `worktreeStatus`
  and `worktreeRemove` — read at `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java`
  § `commit` (742), `worktreeStatus` (854), `worktreeRemove` (704) at `e2aced9` (unchanged at `c8feb0b`); JGit 7.8.0
  `Config.getEnum` throws for an unmatched enum value and `WorkingTreeOptions` reads `core.autocrlf` through it.
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e2aced9 in the PLAN-19 analysis; git diff e2aced9 c8feb0b -- git-worktrees is empty, so unchanged at c8feb0b; JGit 7.8.0 Config.getEnum / WorkingTreeOptions chain re-read there
- OBSERVED: `releaseTarget` lets an `UncheckedIOException` from `Files.list` escape and `attempt` catches `IOException`
  only, so `rollBack` stops before `deleteCreatedBranch` — read at `JGitWorktreeOperations.java` § `releaseTarget`
  (381-382), `attempt` (457-468), `rollBack` (433-447).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e2aced9 in the PLAN-19 analysis; git diff e2aced9 c8feb0b -- git-worktrees is empty, so unchanged at c8feb0b
- OBSERVED: the success path calls `releaseReservation()` at 292, after the working tree is complete, and an
  `IOException` there reaches the add's catch, whose `rollBack` deletes the finished target — read at
  `JGitWorktreeOperations.java` § `run` (292) and § `releaseReservation` (363-369).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e2aced9 in the PLAN-19 analysis; git diff e2aced9 c8feb0b -- git-worktrees is empty, so unchanged at c8feb0b
- OBSERVED: `realPath` is `Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize()`, so a missing path
  does not resolve a symlinked ancestor and the check races — read at `JGitWorktreeOperations.java` § `realPath` (943-945).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e2aced9 in the PLAN-19 analysis; git diff e2aced9 c8feb0b -- git-worktrees is empty, so unchanged at c8feb0b
- OBSERVED: the reflog step of `commit` catches `IOException | IllegalArgumentException` only — read at
  `JGitWorktreeOperations.java` § `commit` (758).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e2aced9 in the PLAN-19 analysis; git diff e2aced9 c8feb0b -- git-worktrees is empty, so unchanged at c8feb0b
- HYPOTHESIS: `Repository.isValidRefName` of JGit 7.8.0 accepts `HEAD`, a leading `-`, `a.lock/b` and `@` where git
  refuses or reads them differently — confirm/refute at JGit 7.8.0 `org.eclipse.jgit/src/org/eclipse/jgit/lib/Repository.java`
  § `isValidRefName` and `git check-ref-format` (verify-at-outline).
- OBSERVED: `InCoreWriter.write` reads the parent tree with `DirCache.read(reader, tree)` outside any handler for
  `IllegalArgumentException`, and JGit 7.8.0 `DirCacheEntry.checkPath` throws `dircache.InvalidPathException`, which
  extends `IllegalArgumentException` — read at `git-core/src/main/java/de/cuioss/git/document/core/InCoreWriter.java`
  § `write` (129-131, catch 143) and JGit 7.8.0 `org/eclipse/jgit/dircache/DirCacheEntry.java` § `checkPath` (864-872),
  `DirCacheBuilder.toEntry` (183).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: InCoreWriter 129-131 and the catch at 143; JGit 7.8.0 DirCacheEntry.checkPath 864-872 throws dircache.InvalidPathException (extends IllegalArgumentException); DirCacheBuilder.toEntry 183
- OBSERVED: the retry loop `for (int attempt = 1; attempt <= attempts; attempt++)` never ends for `attempts =
  Integer.MAX_VALUE` while every update ends `LOCK_FAILURE` — read at `git-core/.../ReferenceUpdater.java` §
  `compareAndSwap` (111) and `compareAndDelete` (193).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: ReferenceUpdater 111 and 193, the for-loop bound is an int compared with <=
- OBSERVED: for a reference that does not exist JGit 7.8.0 `RefUpdate` answers `LOCK_FAILURE` on a name conflict, which
  `compareAndSwap` retries as a lost race, and `refName` is not validated — read at `ReferenceUpdater.java` § `update`
  (245-252), § `compareAndSwap` (112, 122) and JGit 7.8.0 `RefUpdate.java` (714-718).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: ReferenceUpdater 112, 122, 245-252 (no isValidRefName call); JGit 7.8.0 RefUpdate 714-718 returns LOCK_FAILURE on isNameConflicting
- OBSERVED: `checkPaths` refuses only equal paths, and JGit 7.8.0 `DirCacheEditor.deleteOverlappingSubtree` removes a
  file whose path is a directory prefix of a later entry — read at `InCoreWriter.java` § `checkPaths` (151-164) and
  `org/eclipse/jgit/dircache/DirCacheEditor.java` (172-197).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: InCoreWriter 151-164 refuses equal strings only; JGit 7.8.0 DirCacheEditor.deleteOverlappingSubtree 172-197
- OBSERVED: `ReferenceUpdater.parseCommit` calls `RevWalk.parseCommit`, which peels an annotated tag — read at
  `ReferenceUpdater.java` § `parseCommit` (239-243) and JGit 7.8.0 `RevWalk.java` (1133-1139).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: ReferenceUpdater 239-243; JGit 7.8.0 RevWalk.parseCommit 1133-1139 peels via peel(parseAny(id))
- HYPOTHESIS: `SystemReader.getInstance().checkPath` is platform-dependent (`ObjectChecker` with `setSafeForWindows` and
  `setSafeForMacOS` by host), so a Windows name or a trailing dot is accepted on Linux — confirm/refute at JGit 7.8.0
  `org/eclipse/jgit/util/SystemReader.java` § `checkPath` and `org/eclipse/jgit/lib/ObjectChecker.java`
  (verify-at-outline); the call itself is read at `InCoreWriter.java` § `checkPaths` (155).
- OBSERVED: the clone removes only user name and password from the stored origin — read at
  `git-core/.../JGitRepositoryOperations.java` § `cloneIfMissing` (251, `setUser(null).setPass(null)`).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: JGitRepositoryOperations 251 setUser(null).setPass(null)
- HYPOTHESIS: a token in the query string, and an uppercase-scheme URL that `URIish` parses as a local path, stay in the
  clone's stored origin — confirm/refute at `JGitRepositoryOperations.java` § `cloneIfMissing` (251) and JGit 7.8.0
  `org/eclipse/jgit/transport/URIish.java` § `setUser` / `toString` (verify-at-outline).
- OBSERVED: `readFile` reads a blob with `content.readAllBytes()` and the accepted exception rests on "a source the
  remote policy admitted" — read at `JGitRepositoryOperations.java` § `readFile` (708) and `doc/specification/git-core.adoc`
  § GDR-CORE-8 (363-365).
  - verdict: corroborated | checked_at: c8feb0bb6af2baaa79f66736b8f9d5b6f591fe8c | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c8feb0b: JGitRepositoryOperations 708 content.readAllBytes(); git-core.adoc 363-365 states the accepted exception with that reason
- HYPOTHESIS: the `git-testing` leftovers of PLAN-18 (the tautological positive half of the `@InMemory` type assertion,
  the unobserved rollback wiring, the unasserted "auto-close off" setting, the lost first close failure, the stale
  `PER_CLASS` Javadoc) still hold after PR #29 — confirm/refute at
  `git-testing/src/test/java/de/cuioss/git/document/testing/InMemoryRepositoryExtensionTest.java`,
  `LocalRepositoryExtensionTest.java`, `git-testing/src/main/java/de/cuioss/git/document/testing/TemporaryDirectories.java`
  and the Javadoc of the three extensions (verify-at-outline; as of PLAN-18's landing, not re-read against `b5ee5d7`).
- Verify-first clause: the three-layer analyses of PLAN-04 and PLAN-19 read the code only (no build, no test run); every
  OBSERVED claim above is re-read at the plan's own HEAD at outline, and a refuted one is dropped from the deliverable
  that names it. Item (b) of deliverable 3 and item (b) of deliverable 4 each end in an owner decision: put the
  question before the work, not after it.

## Expected Surface

- OBSERVED: `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `git-worktrees/src/test/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- OBSERVED: `git-core/src/test/java/de/cuioss/git/document/core/`
- OBSERVED: `git-testing/src/main/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-testing/src/test/java/de/cuioss/git/document/testing/`
- OBSERVED: `doc/specification/git-worktrees.adoc`
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `doc/specification/git-testing.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (GDR-GEN-3 table, if an outcome changes)
- HYPOTHESIS: `doc/clients/pm-mcp-core.adoc` (the outcomes of the worktree operations, if one changes)
- HYPOTHESIS: `AGENTS.md` ("Current state", if a public type or method changes)
- HYPOTHESIS: `git-worktrees/doc/LogMessages.adoc` and `git-core/doc/LogMessages.adoc` (only if a log message is added)

## Dependencies and Sequencing

- Collection plan: **hold it back from `next` until the operator releases it.** `analyze` folds further leftovers into
  this spec as new deliverables or as items of a deliverable, in the same edit as their Expected Surface; the operator
  releases the plan by naming it in a `next` round. It is queued at the end of WS-01.
- Depends on: PLAN-04 (landed as PR #35, merge commit `c8feb0b`), PLAN-19 (PR #31, `e2aced9`) and PLAN-18 (PR #28).
- Overlaps with: PLAN-05 (same `git-core` package, `doc/specification/git-core.adoc`, `AGENTS.md` and `doc/README.adoc`
  if it edits them) — release this plan after PLAN-05 lands, or accept the overlap at the pairing prompt. It does not
  overlap with PLAN-06 to PLAN-17 except through `AGENTS.md`, `doc/specification/general.adoc` and `pom.xml`, which a
  folded-in leftover may touch.
- Adjacent to: nothing in `git-store/` yet — PLAN-08 and PLAN-09 are the first consumers of `compareAndSwap` and
  `compareAndDelete`, so the items of deliverable 3 are cheaper to fix before them.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-20-leftover-fixes-2.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
