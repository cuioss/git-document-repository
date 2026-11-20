# PLAN-20: Collected leftover fixes (git-worktrees, git-core, git-testing, build guards)

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Collect the leftovers the three-layer analyses of landed plans find — the layer-3 defects of PLAN-19 (`git-worktrees`)
and PLAN-04 (`git-core`), the minor layer-3 defects of PLAN-07 (`git-store`), the test gaps beside them, the `git-testing` leftovers of PLAN-18 and the residue of PLAN-06's build guards, which have no other
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
   test builds such a tree with an `ObjectInserter`; `InCoreMerger.merge` (PLAN-05) has the same reach, because JGit's
   merger builds a `DirCacheEntry` for every tree entry, and gets the same result and a test; (b) the unchecked exceptions of caller code and configuration
   (`Signer`, `gpg.format` through `GpgConfig`, `TreeOperation.apply`, a `null` result or element, and — a lead to
   confirm first — a `LargeObjectException` from the notes tree source of `GitNotes.write`) are either returned
   as `FAILED` with the class name or stated in the Javadoc as the caller's contract, decided and recorded, with a
   test; (c) the retry loops of `compareAndSwap` and `compareAndDelete` cannot overflow for `attempts =
   Integer.MAX_VALUE`, or the bound is validated and documented; the test terminates at the base too (a repository
   double ends the loop after a fixed number of calls and the assertion names the number); (d) `compareAndSwap` tells
   a name conflict (`refs/heads/a/b` while `refs/heads/a` exists) from contention, running the operation once and not
   on every attempt, and a `refName` that is not a valid reference name (`refs/heads/../../../outside/x`) is refused
   before any file is touched by `compareAndSwap`, `compareAndDelete` and `compareAndSwapTree` (the notes entry points of
   `GitNotes` already validate), with the outcome `git-worktrees` uses for an invalid name (`REFUSED_BY_POLICY`, no new
   constant), and a notes reference that name-conflicts with an existing one (`refs/notes/a/b` while `refs/notes/a`
   exists) is reported as that through `GitNotes.write`, not as a lost race; (e) edits that overlap as a file and a directory below it (`Put("a")` with `Put("a/b")`) are refused like a
   repeated path, or the loss is documented with a test; (f) `ReferenceUpdater.parseCommit` resolves with `parseAny`
   and checks the type, so a reference that points at an annotated tag is a result, not a silent peel; (g) the
   platform dependence of the path check (`SystemReader.checkPath`: Windows names and a trailing dot accepted on
   Linux, case-fold duplicates, an installed `SystemReader`) is put to the owner through the question tool — one
   strictness on every host, or the host's rules, documented — and the choice is recorded in `git-core.adoc`; (h) the guarded batch for symbolic references (PLAN-05): the
   Javadoc of `unresolvedUnderLock` is corrected (a held lock on the file backend also leaves the old id unset, because
   `RefDirectoryUpdate.tryLock` returns before it sets one; the batch is then refused and reported as the same
   `LOCK_FAILURE`), a test pins the JGit 7.8.0 message text (`io error`) the lost-race classification relies on so an
   upgrade fails loudly, and the fall-through of a refused batch to `update.update()` for a dangling alias, which cannot
   happen at 7.8.0 but would replace the alias without an id comparison on the in-memory backend, is closed or recorded.
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
   collide in a `Map.of`; (d) `JGitRepositoryOperations.log` (reworked by PLAN-05) reads one commit past `maxCount` and does
   not catch the unchecked `RevWalkException` of a missing parent, the defect PLAN-05's review fixed in
   `RepositoryReader.history`: drive `walk.next()` and return `FAILED`, with a test whose commit after the limit has a
   missing parent; (e) the `git-core` tests use hand-written doubles (`Proxy`, lambdas, a fake `Transport`) where AGENTS.md
   prescribes EasyMock, although the approval list names it: EasyMock replaces them where it can (a test-scope entry in
   `git-core/pom.xml`, covered by the standing approval) or the exception is recorded with its reason.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
5. `git-testing` leftovers of PLAN-18's landing (`landings/PLAN-18.md` L1-5, L1-6, L2-1 to L2-5, L3-1, L3-2), each
   re-read against the code at the plan's head first because PR #29 rewrote two extension tests since the analysis,
   then fixed or tested, or recorded as an accepted exception with its reason in `doc/specification/git-testing.adoc`:
   the positive half of the `@InMemory` type assertion is tautological; the rollback wiring from `createBareRepository` to
   `rollBack` is unobserved; nothing asserts that the "auto-close off" Surefire run received its setting; `TemporaryRepository.close`
   loses the first failure when closing and deletion both fail; the public Javadoc of the three extensions is stale
   under `PER_CLASS`; `GitCredentialGeneratorTest` never held a double (a premise to drop if the read confirms it);
   and, from PLAN-05's review, the fixtures do not isolate a test from the developer's user and system git
   configuration (PLAN-05's `RedirectEndToEndTest` was stopped first by `http.followRedirects=false` and, in the next round,
   by `http.maxRedirects=0` from the user's configuration, before the guard under test ran): decide whether `git-testing`
   offers a hermetic configuration (for example a `SystemReader` without user or system configuration) or records the
   limit with what a test has to pin, after reading which existing tests depend on the host's configuration; and, from
   three consecutive plans, a flake of one kind: a negative assertion that a generated letters-only value does not
   appear in free text (PLAN-04's `RepositoryOperationsRecordsTest` line 116, PLAN-06's `JGitRepositoryOperationsTest`
   `refusedRedirectLogged`, which failed for seed `-3633849498368272603` while the same source had passed minutes
   earlier) or that two generated fixture contents differ (PLAN-05's merge conflict fixtures): `git-testing` offers a
   generator for a value made distinctive by a fixed marker, or the rule and the helper a test has to use are recorded in
   `git-testing.adoc`, and the tests of that kind in `git-core` use it.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:javadoc
   - Persona: module-tester

6. Build guards and native-image residue (leftovers of PLAN-06's analysis, `landings/PLAN-06.md`): (a) the
   `consumer-isolation` rule of `git-core/pom.xml` does not see a direct optional dependency (Enforcer 3.6.3 drops it
   when `searchTransitive` is true), so a new optional compile dependency of `git-core` passes the build without an
   allow-list edit or the owner's approval: a rule or check bans every direct third-party dependency of `git-core` outside
   the approved list whatever its `optional` flag (the same rule with `searchTransitive=false` has no optional filter),
   shown by a deliberately wrong optional dependency; (b) the guards are proven by negative runs that are recorded only
   in the squash message of PR #39 (`git-testing` at scope `compile` in `git-worktrees`; `cui-test-generator` at scope
   `compile` in `git-core`): record the runs (the edit, the command, the failure message) in `general.adoc`, or make
   them repeatable by a check that needs no new library (a new library goes to the owner first); (c) the trade-offs the
   review raised and the lane accepted (the four domain terms copied into three module tests, the allowed-edge
   includes that mirror `module-structure.adoc` by hand, a module widening its own edges in its own POM) are recorded as
   accepted exceptions with their reasons in `general.adoc`, or derived; (d) `NativeImageMetadataTest` also asserts the
   top-level shape of each file (an array of objects for `reflect-config.json`, an object for `resource-config.json`) and
   that a removed entry fails it, because the regular-expression extraction passes a malformed file; (e) the wording of
   `README.adoc` and `general.adoc` ("the JGit classes it reaches") matches what is listed (one class, one bundle): decide,
   after the DFS lead of the claims, whether `DfsText` is reached and either list it or word the documents to what is listed.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:manage-maven-profiles
   - Persona: implementer

7. `git-store` leftovers of PLAN-07's landing (`landings/PLAN-07.md` L3-2 to L3-5, L2-1 to L2-3; L3-1, the bound of
   `save.max-attempts`, is folded into PLAN-08), each first re-read at the plan's head: (a) `StoreRepository.toString`
   (and so `RepositoryRegistry.toString` and `StoreConfiguration.toString`) removes user and password only, so a token in a
   remote's path or query (`https://host/repo.git?token=x`) is rendered, against GDR-NFR-3: render the remote without its
   query and credential-bearing parts, or refuse such a remote at the registry, tested with a JUL capture and a rendering
   check; (b) `StoreProviders.select` calls `idOf.apply(candidate)` unguarded, so a candidate whose `id()` throws escapes as
   an exception instead of a `StoreResult` (GDR-GEN-3): return `MALFORMED` naming the interface, tested with a throwing
   provider, and decide whether a `LinkageError` from loading is treated alike; (c) `StoreProviders.load` instantiates every
   `AnchorProvider` on the class path even when `anchor.provider` is absent, so a broken anchor jar stops a start that does
   not use it, and `GIT_STORE-1` logs the selected ids while the Javadoc argues a refusal omits the id because a configured
   value may be secret: instantiate the anchor providers only when `anchor.provider` is set, and decide, with the owner when
   it changes a documented behaviour, whether the selected ids may be logged; (d) `StoreConfiguration.anchorKeyIsSigningKey`
   compares the two key strings, so the same key under another spelling (`/k`, `/./k`) passes GDR-STORE-18: normalise a
   path-like key before comparing, or record the limit and its owner (the `signed` provider) as an accepted exception in
   `git-store.adoc`; (e) `CodecTest` proves byte identity only for a codec that lives in the test, so no `git-store` change
   can fail it: either give the contract code to check (a verifying helper or a test-kit class other modules reuse) or record
   it as a documented contract test; (f) provider selection is tested on the class path only (`useModulePath=false`, the
   registrations in `META-INF/services`), so lookup in module mode (`provides`) is not exercised: add one module-path
   test run or record the limit in `git-store.adoc`; (g) the four domain terms are now copied into four `DomainTermAbsenceTest`
   classes (`git-core`, `git-worktrees`, `git-testing`, `git-store`): item (c) of deliverable 6 covers the three, extend it
   to the fourth.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:java-security
   - Persona: implementer

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS, `@NullMarked` packages with `@Nullable` where needed, results instead of exceptions
  for expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a documented log-message
  catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and method, license header
  via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the `git-testing` fixtures and
  without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result
  detail, exception message, log message, stored configuration or commit metadata.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- A guard is proven by a deliberately wrong input that fails it, recorded or repeatable; the guards of deliverable 6 are the
  only items of this plan that change the build configuration, and a new plugin or library goes to the owner first.
- Every behaviour fix is first reproduced by a test that fails at the base and passes at the head; an item whose
  premise the re-read at outline refutes is dropped from the deliverable that names it. A test of a guard pins every
  setting that could stop the run before the guard is reached (the whole related configuration group, not the one key
  a reviewer named), takes fixture content that is distinct by construction rather than from independent random draws,
  and fails on the regressed code (its fixture breaks the forbidden behaviour, for example with a missing object).
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

- `verify` passes on the reactor at the plan's head, and every behaviour fix of deliverables 1 to 4 and of items (a), (b) and (c) of deliverable 7 has a test that
  fails at `e43c7e915a9d0667117b72970bc156bbe73b6506` (`main` after PLAN-06, which changed no `git-core` or `git-worktrees`
  code, so every defect below is present as at `14269fe`; the `git-worktrees` ones are present since `e2aced9`) and
  passes at the plan's head.
- Every item of deliverables 1 to 7 is either fixed and tested at the plan's head, or recorded as an accepted exception
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
- OBSERVED: `InCoreMerger.merge` catches `NoMergeBaseException` and `IOException` only, and JGit 7.8.0
  `StrategySimpleTwoWayInCore` builds `new DirCacheEntry(tw.getRawPath(), stage)` for every tree entry, which throws
  `InvalidPathException` — read at `git-core/.../InCoreMerger.java` § `merge` (124, catches 135-142) at `14269fe` and JGit
  7.8.0 `org/eclipse/jgit/merge/StrategySimpleTwoWayInCore.java` § `add` (152-165).
  - verdict: corroborated | checked_at: 14269fef44b97f690f104f420c88ef69db6d5c3d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 14269fe: InCoreMerger 124 and catches 135-142; JGit 7.8.0 StrategySimpleTwoWayInCore.add 148-165 builds new DirCacheEntry(rawPath, stage) which throws InvalidPathException
- OBSERVED: `JGitRepositoryOperations.log` iterates `for (RevCommit commit : walk)` and checks `maxCount` inside the loop, and
  catches `IOException | IllegalArgumentException` while JGit's `RevWalkException` is a `RuntimeException` — read at
  `JGitRepositoryOperations.java` § `log` (473-482) at `14269fe`.
  - verdict: corroborated | checked_at: 14269fef44b97f690f104f420c88ef69db6d5c3d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 14269fe: JGitRepositoryOperations 468-490 for-each over the walk, catch IOException | IllegalArgumentException; JGit 7.8.0 RevWalkException extends RuntimeException
- OBSERVED: the Javadoc of `ReferenceUpdater.unresolvedUnderLock` says a `LOCK_FAILURE` with an old id is a real one on the
  file backend, while JGit 7.8.0 `RefDirectoryUpdate.tryLock` returns false before `setOldObjectId` when the lock is held —
  read at `ReferenceUpdater.java` § `unresolvedUnderLock` (376-384) at `14269fe` and `org/eclipse/jgit/internal/storage/file/RefDirectoryUpdate.java` (46-60).
  - verdict: corroborated | checked_at: 14269fef44b97f690f104f420c88ef69db6d5c3d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 14269fe: ReferenceUpdater 372-390 Javadoc; JGit 7.8.0 RefDirectoryUpdate.tryLock 46-60 returns false before setOldObjectId
- OBSERVED: `GitNotes.write` validates the notes reference by syntax only (`Repository.isValidRefName`), and
  `ReferenceUpdater` calls `isValidRefName` nowhere — read at `GitNotes.java` § `isNotesRef` (205-208) and a search of
  `ReferenceUpdater.java` at `14269fe`.
  - verdict: corroborated | checked_at: 14269fef44b97f690f104f420c88ef69db6d5c3d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 14269fe: GitNotes 205-208 isNotesRef uses Repository.isValidRefName; a search of ReferenceUpdater at 14269fe finds no isValidRefName
- OBSERVED: the `consumer-isolation` rule drops direct optional dependencies: it uses `searchTransitive=true`, and Enforcer 3.6.3
  `ResolverUtil.resolveTransitiveDependenciesVerbose` calls `resolveTransitiveDependencies(true, false, true, ...)`, whose third
  flag filters every `Dependency.isOptional()` — read at `git-core/pom.xml` § `consumer-isolation` (83-115) at `e43c7e9` and
  the `javap` bytecode of `org/apache/maven/enforcer/rules/dependency/ResolverUtil` in `enforcer-rules-3.6.3.jar`.
  - verdict: corroborated | checked_at: e43c7e915a9d0667117b72970bc156bbe73b6506 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e43c7e9: git-core/pom.xml 83-115 (searchTransitive true); javap of ResolverUtil in enforcer-rules-3.6.3.jar: resolveTransitiveDependenciesVerbose calls resolveTransitiveDependencies(true,false,true,list) and the lambda filters Dependency.isOptional() on the third flag
- OBSERVED: `NativeImageMetadataTest` extracts names with a regular expression and parses no JSON, and the two metadata files list
  one class and one bundle (`org.eclipse.jgit.internal.JGitText`), while `README.adoc` says `git-core` ships the metadata "for
  the JGit classes it reaches" — read at `git-core/src/test/java/de/cuioss/git/document/core/NativeImageMetadataTest.java`
  (58-59, 101-108), `git-core/src/main/resources/META-INF/native-image/de.cuioss/git-core/` and `README.adoc` (52-53) at `e43c7e9`.
  - verdict: corroborated | checked_at: e43c7e915a9d0667117b72970bc156bbe73b6506 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e43c7e9: NativeImageMetadataTest 50-109 (regex QUALIFIED_NAME, no JSON parse), reflect-config.json and resource-config.json list JGitText only, README.adoc 52-53 says the JGit classes it reaches
- OBSERVED: the negative runs that prove the two guards are recorded only in the squash message of PR #39 — read at
  `git show e43c7e9` § Test Plan (the diff `364dc50..e43c7e9` holds no test of the layering rule).
  - verdict: corroborated | checked_at: e43c7e915a9d0667117b72970bc156bbe73b6506 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e43c7e9: git show -s e43c7e9 Test Plan lists both negative runs; the diff 364dc50..e43c7e9 holds no layering test (only DomainTermAbsenceTest x3 and NativeImageMetadataTest as new tests)
- HYPOTHESIS: a code path of `git-core`, or of a consumer that uses an in-memory (DFS) repository with it, reaches
  `org.eclipse.jgit.internal.storage.dfs.DfsText` or `RepoText`, which the metadata does not list — confirm/refute at JGit 7.8.0
  `org/eclipse/jgit/internal/storage/dfs/DfsText.java` (callers) and the main sources of `git-core` (a search for `DfsText` and
  `storage.dfs` finds none at `e43c7e9`) (verify-at-outline).
- HYPOTHESIS: the `git-testing` leftovers of PLAN-18 (the tautological positive half of the `@InMemory` type assertion,
  the unobserved rollback wiring, the unasserted "auto-close off" setting, the lost first close failure, the stale
  `PER_CLASS` Javadoc) still hold after PR #29 — confirm/refute at
  `git-testing/src/test/java/de/cuioss/git/document/testing/InMemoryRepositoryExtensionTest.java`,
  `LocalRepositoryExtensionTest.java`, `git-testing/src/main/java/de/cuioss/git/document/testing/TemporaryDirectories.java`
  and the Javadoc of the three extensions (verify-at-outline; as of PLAN-18's landing, not re-read against `b5ee5d7`).
- HYPOTHESIS: no `git-testing` fixture isolates a test from the user's and the system's git configuration, so a test of
  a guard can be stopped by a host setting before the guard runs (PLAN-05's review round 1 and 2: `http.followRedirects`,
  `http.maxRedirects`) — confirm/refute at `git-testing/src/main/java/de/cuioss/git/document/testing/LocalRepositoryExtension.java`
  and `TemporaryDirectories.java` and the `SystemReader` use of `git-core` `RefusingFS` (verify-at-outline).
- HYPOTHESIS: `GitGenerators` offers no generator for a value that cannot collide with free text, so each test prefixes by
  hand (PLAN-04 and PLAN-06 each fixed one flake that way) — confirm/refute at
  `git-testing/src/main/java/de/cuioss/git/document/testing/GitGenerators.java` and the tests of `git-core` that assert a
  generated value is absent from a log record or a rendering (verify-at-outline).
- OBSERVED: `StoreRepository.toString` renders a remote through `new URIish(remote).setUser(null).setPass(null).toString()`, which keeps
  the path and the query, so a token in either is printed — read at `git-store/src/main/java/de/cuioss/git/document/store/StoreRepository.java`
  70-84 at `a558afd`.
- OBSERVED: `StoreProviders.unique` calls `idOf.apply(candidate)` without a guard, and `StoreProviders.load` instantiates every
  `AnchorProvider` through `loadAll` before it reads `anchor.provider` — read at
  `git-store/src/main/java/de/cuioss/git/document/store/StoreProviders.java` 78-90, 158-175 at `a558afd`.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreRepository.java 70-84 re-read at a558afd: withoutUserInformation returns new URIish(remote).setUser(null).setPass(null).toString(), which keeps path and query; read by the three-layer leaf and re-read by the orchestrator
- OBSERVED: `StoreConfiguration.anchorKeyIsSigningKey` compares `signed.key()` and `signing.key()` with `String.equals` — read at
  `git-store/src/main/java/de/cuioss/git/document/store/StoreConfiguration.java` 164-168 at `a558afd`.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreProviders.java 78-90 (load instantiates AnchorProvider via loadAll before anchor.provider is read) and 158-175 (unique calls idOf.apply(candidate) unguarded) re-read at a558afd
- OBSERVED: `CodecTest` holds the only `Codec` implementation of the module's tests (`KeyValueCodec`, a private nested class) and
  Surefire runs with `useModulePath=false` — read at `git-store/src/test/java/de/cuioss/git/document/store/CodecTest.java` 125-130
  and `git-store/pom.xml` 100 at `a558afd`.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreConfiguration.java 164-168 re-read at a558afd: anchorKey.equals(signing.key()) && anchorKeySource.equals(signing.keySource())
- Verify-first clause: the three-layer analyses of PLAN-04, PLAN-05 and PLAN-19 read the code only (no build, no test run);
  the `git-core` line references above are those of `c8feb0b`, and PLAN-05 moved them (`landings/PLAN-05.md` names where each
  defect P1 to P10 sits at `14269fe`, all still present); every
  OBSERVED claim above is re-read at the plan's own HEAD at outline, and a refuted one is dropped from the deliverable
  that names it. Item (b) of deliverable 3 and item (b) of deliverable 4 each end in an owner decision: put the
  question before the work, not after it.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: CodecTest.java 125-130 re-read at a558afd: KeyValueCodec is a private nested class of the test and the only Codec implementation; git-store/pom.xml 100 sets useModulePath=false

## Expected Surface

- OBSERVED: `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `git-worktrees/src/test/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- OBSERVED: `git-core/src/test/java/de/cuioss/git/document/core/`
- OBSERVED: `git-testing/src/main/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-testing/src/test/java/de/cuioss/git/document/testing/`
- OBSERVED: `pom.xml`
- OBSERVED: `git-core/pom.xml`
- HYPOTHESIS: `git-core/src/main/resources/META-INF/native-image/`
- OBSERVED: `git-store/src/main/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/src/test/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/pom.xml`
- OBSERVED: `doc/specification/git-store.adoc`
- HYPOTHESIS: `README.adoc` (the native-image wording, if it changes)
- OBSERVED: `doc/specification/git-worktrees.adoc`
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `doc/specification/git-testing.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (GDR-GEN-3 table, if an outcome changes)
- HYPOTHESIS: `doc/clients/pm-mcp-core.adoc` (the outcomes of the worktree operations, if one changes)
- HYPOTHESIS: `AGENTS.md` ("Current state", if a public type or method changes)
- HYPOTHESIS: `git-worktrees/doc/LogMessages.adoc` and `git-core/doc/LogMessages.adoc` (only if a log message is added)

## Dependencies and Sequencing

- Seven deliverables, over the scope-bloat guard (about six): it proceeds unsplit because the operator asked for one collection
  plan, each deliverable is independent and touches its own module, and deliverable 7 was added at the operator's word on
  2026-11-20 (PLAN-07's landing). The operator may split it when releasing it; the natural cut is by module (deliverables
  1, 2 and the `git-worktrees` part of 6 move with PLAN-21; 3 and 4 `git-core`; 5 `git-testing`; 7 `git-store`).
- Collection plan: **hold it back from `next` until the operator releases it.** `analyze` folds further leftovers into
  this spec as new deliverables or as items of a deliverable, in the same edit as their Expected Surface; the operator
  releases the plan by naming it in a `next` round. It is queued at the end of WS-01.
- Depends on PLAN-21 (merge of `git-worktrees` into `git-core`, staged 2026-11-20, owner's call): after it the worktrees code,
  its tests and its specification part live in `git-core` (`git-core/src/main/java/de/cuioss/git/document/core/worktrees/`,
  `git-core.adoc` § Working Trees), `git-worktrees/` is gone and the module-layering include and the domain-term test of
  that module with it. The paths, module names and surface entries of deliverables 1 and 2 and of the claims that name
  `git-worktrees/` are re-grounded by the orchestrator when PLAN-21 lands, in the same act that stamps their verdicts; do
  not emit this plan before that.
- Also depends on: PLAN-04 (landed as PR #35, merge commit `c8feb0b`), PLAN-05 (PR #37, `14269fe`), PLAN-06 (PR #39, `e43c7e9`),
  PLAN-19 (PR #31, `e2aced9`) and PLAN-18 (PR #28).
- Overlaps with: nothing in flight. PLAN-05 and PLAN-06, which shared entries with it, have shipped. It shares the root
  `pom.xml`, `AGENTS.md` and `doc/specification/general.adoc` with the later module-creating plans (PLAN-07 adds a module line
  and an allowed-edge block), so the pairing gate puts the pair to the operator when both are launched at once.
- Deliverable 7 edits `git-store/`, which PLAN-08..11 also edit: strictly sequential with them, queue order decides, and the
  gate reads `git-store/` from `a558afd` on. PLAN-08 takes L3-1 of PLAN-07 (the bound of `save.max-attempts`) itself.
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
