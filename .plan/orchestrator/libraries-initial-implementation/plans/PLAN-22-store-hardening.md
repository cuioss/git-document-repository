# PLAN-22: git-store hardening (the leftovers of PLAN-07, PLAN-08 and PLAN-09)

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fix the minor defects, test weaknesses and documentation gaps that the three-layer analyses of the landed `git-store` plans
found and that no running plan owns: the leftovers of PLAN-07 (the foundation), PLAN-08 (drafts, save, expiry) and PLAN-09
(release, index, listeners, the save step), collected in one plan after PLAN-11 instead of one plan per landing. None of the
items gives a wrong result on the default path of a single writer; each needs a failure in the middle of an operation, a race, a
foreign tool's reference, an unusual entry or a malformed configuration. The first two deliverables moved here from the held-back
collection plan PLAN-20 (its former deliverables 7 and 8) at the operator's word on 2026-11-22; deliverable 3 is PLAN-09's.
Later analyses of PLAN-10 and PLAN-11 fold their `git-store` leftovers into this spec until the operator releases it.

## Deliverables

1. `git-store` leftovers of PLAN-07's landing (`landings/PLAN-07.md` L3-2 to L3-5, L2-1 to L2-3; L3-1, the bound of
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
   test run or record the limit in `git-store.adoc`; (g) the four domain terms are copied into the `DomainTermAbsenceTest`
   classes of `git-core`, `git-testing` and `git-store` (PLAN-21 removed the fourth copy with `git-worktrees`): item (c) of
   deliverable 6 of PLAN-20 names all three, so nothing remains here but that `git-store` is among them.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:java-security
   - Persona: implementer
2. `git-store` leftovers of PLAN-08's landing (`landings/PLAN-08.md` L3-1 to L3-5 and L2-1 to L2-3; added at the operator's
   word on 2026-11-21; items (i) and (j) are a flaky-test and tidy-up residue from PLAN-09's landing, folded in on
   2026-11-22), each first re-read at the plan's head, behaviour fixes reproduced by a test that fails at the base:
   (a) `RepositoryGuard` keeps one `ReentrantLock` per draft reference ever saved and one guard per clone path, never
   removed (`RepositoryGuard.java` 45-57), so a long-running JVM that saves many per-document drafts grows without bound:
   release a draft lock when no save holds it and a guard with its last writer, or record the limit with its reason in
   `git-store.adoc`; (b) `ExplicitSave` retries a permanent `REJECTED` (a protected branch, a server hook) like a lost lease,
   up to `save.max-attempts` (at most 100) fetch-and-push rounds under the clone-wide transport lock and with no pause
   (`ExplicitSave.java` 235-240), which also holds back an expiry waiting for the write lock: tell a rejection that cannot
   change from a lost lease, or pause between attempts, or record the bound as the accepted behaviour; (c) `ExplicitSave`
   answers an `expectedBlobIds` value that is not a full object id with `NOT_FOUND` (202-204), the outcome of a missing
   draft, while every other malformed input of the save is `MALFORMED`-class: return the outcome the other malformed
   inputs use, naming the argument and never the value, and update the Javadoc, `git-store.adoc` and the tests; (d)
   `DraftExpiry.deleteIfStale` (201) and `DraftReferences.list` (233) call `parseCommit` on the tip of a reference below
   `refs/drafts/`, so one that is not a commit (written by a foreign tool) ends every expiry and the listing of that user
   as `FAILED`: skip it with a log record naming the class only and carry on, or record the limit; (e) the garbage
   collection runs with `pruneExpire=now` under the in-process guard only (`DraftExpiry.java` 222), so another JVM or a
   `git-core` fetch outside `StoreWriter` on the same clone can lose objects received but not yet referenced: decide with
   the JGit 7.8.0 sources read (`GC.deleteOldPacks`, `PidLock`) whether the `gc.pid` lock and JGit's own locking suffice, and
   record the decision in `git-store.adoc` § GDR-STORE-7; (f) the landing's "no suppression was used" is false (one
   `@SuppressWarnings("java:S4449")` added to `StoreConfiguration.readCommit`, two `unchecked` ones to `DraftExpiryTest` and
   `StoreWriterDraftTest`): fix the cause or record each with its reason (a Sonar dismissal is the operator's, see the
   constraints); (g) `ConcurrentWritersTest.tenWritersWithOwnClones` (237) asserts the outcome only, so it passes with no
   contention at all and does not touch the `attempts` boundary: make the writers race (a barrier at the lease, or a pause in
   a repository double) and assert that at least one writer retried and that a writer which loses nine times still ends
   `Saved` with `save.max-attempts` 10; (h) `StoreReferences.collections`, the "declared twice" refusal of GDR-STORE-2, had no
   caller in main code at `c1e02fc`; at `4a5eed5` only `StoreIndex.open` (184) calls it, and the writer does not, so a writer
   opened on a configuration that declares a collection both per-document and numbered is not refused: call it where the
   configuration meets the writer, or record that the consumer calls it and test that path; and, from PLAN-09's landing
   (`landings/PLAN-09.md`, added 2026-11-22), (i) two flakes that failed the quality gate three times on code the branch had
   not changed and passed on re-run (the first, `JGitRepositoryOperationsTest.publishLosesRace` in `git-core`, is item (f) of
   PLAN-20 deliverable 4): `ConcurrentWritersTest.tenWritersWithOwnClones`
   (a `DirectoryNotEmptyException` on `objects/pack` when the fixture deletes the temporary remote, twice, and a lost lease on
   all ten attempts once: make the fixture's cleanup wait for or retry the deletion, and reproduce the lost lease with a
   barrier; PLAN-12's run saw it again on four CI runs with four different errors, one of them "Failed to close extension
   context", and a red build the pre-push gate accepted; lessons `2026-11-22-21-001` and `-004`: log the four errors verbatim,
   check how the writers start (a barrier or latch, not sleeps), whether each has its own repository handle, and whether the
   compare-and-swap loser may retry, then fix it in its own change; PLAN-23's run saw it once more, under whole-tree load, with one of
   the ten writers refused on every attempt, passing alone 4 of 4 and on re-run: the fifth plan to meet it); (j) the private duplicates PLAN-09 left in `git-store`: three byte-identical copies of `name(StoreResult.Outcome)`
   (`DocumentRelease`, `StoreWriter`, `ExplicitSave`) and new private copies of `HEADS` and `ORIGIN` beside
   `StoreReferences.HEADS` and `ExplicitSave.ORIGIN` (a fourth copy of `name` now sits in `StoreClone` 203): one shared home
   each, package-private; the measurement scope of `IndexFootprintTest` (it also walks the repository the index reads) is item
   (k) of deliverable 3. Line numbers of this deliverable are those of `c1e02fc`; at `4a5eed5` the `RepositoryGuard` maps sit
   at 48-58, the `NOT_FOUND` for a malformed expected blob id at `ExplicitSave` 232-233 and its attempt loop at 270, the rest
   unchanged.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:java-security
   - Persona: implementer

3. `git-store` leftovers of PLAN-09's landing (`landings/PLAN-09.md` L2-1 to L2-5, L3-1 to L3-11, L1-4 and L1-5; added at the
   operator's word on 2026-11-22), each first re-read at the plan's head, behaviour fixes reproduced by a test that fails at the
   base: (a) `DocumentRelease.pushTag` reports INCOMPLETE for a complete release when the tag already exists at the right
   commit: git-core maps an up-to-date push against the zero-id lease to `LEASE_REFUSED` (`JGitRepositoryOperations.java`
   872-876), so when a second process pushed the tag first, the first process's `pushTag` (259-265) logs `GIT_STORE-105` and
   returns INCOMPLETE, and a repeated release counts `v<n+1>` and writes a second, empty merge commit: on a refused tag push read
   the remote tag and treat the same commit as complete, with a two-writer test; (b) the local release tag created at
   `DocumentRelease.java` 223 stays in the clone when the push of `main` ends REJECTED, UNREACHABLE or with any other
   non-lease error, and only a later pruning fetch removes it, so `StoreIndex.snapshot("<collection>/<id>/v<n>")` resolves it
   and indexes a commit that was never released: delete the local tag when the push of `main` fails, tested with a
   protected-branch rejection that also asserts the local tag is gone; (c) a source entry with gitlink mode (160000) in a
   changed or new path is mapped to `REGULAR_FILE` (`DocumentRelease.mode`, 348-355) and then read as a blob
   (`repository.open(..., OBJ_BLOB)`, 335), which throws `MissingObjectException`, so the release ends FAILED on every
   attempt: carry the gitlink or refuse it with a defined outcome, tested; (d) every isolation point catches
   `RuntimeException` only (`StoreEvents.call` 179, `ListenerDispatch.call` 272, `StoreIndex.contribute` 443,
   `ExplicitSave.step` 350, `StoreClone.periodicFetch` 198), so an `Error` from a listener, a contributor or a step (an
   `AssertionError`, a `StackOverflowError`, a `NoClassDefFoundError`) propagates out of save, release or fetch after the
   push succeeded, skips the remaining observers and the index refresh, and kills the periodic fetch schedule: decide the
   handling (catch `Exception` and a defined handling of `LinkageError` and `AssertionError`, or document that an `Error`
   propagates and keep the schedule alive), against "a failing listener never fails the trigger" of GDR-STORE-10, tested; the
   five `// cui-rewrite:disable InvalidExceptionUsageRecipe` comments follow the decision; (e) `ExplicitSave.edits` rebuilds
   every put as `new TreeEdit.Put(path, content)` (`ExplicitSave.java` 597), i.e. `REGULAR_FILE`, so a draft written with
   `EXECUTABLE_FILE` or `SYMLINK` (possible since `TreeEdit.Mode`) is saved as `100644` while the release keeps the mode: carry
   the draft's mode through the save and the save step's edits, and compare file modes as well as bytes in the tests; (f)
   `StoreIndex.refresh` (296-320) is all-or-nothing: one unreadable object on one branch (a missing blob on a single working
   branch, `StoreIndexTest` 786) throws `IOException`, drops the whole refresh, leaves `stale` set and freezes `main` and every
   other source, so every later operation repeats the full refresh: isolate the failure per source, report the source, keep
   the others fresh, tested; (g) a save step or a contributor that calls back into the writer is guarded by Javadoc only: the
   step runs under the shared maintenance lock, the draft lock and the transport lock, so a step that calls
   `writer.expireDrafts()` requests the write lock while its own thread holds the read lock of a `ReentrantReadWriteLock`,
   which cannot upgrade, and the save deadlocks for good (`RepositoryGuard.java` 89-102, `DraftExpiry.java` 129), and a slow step
   blocks every fetch and save of the clone: detect the re-entry and refuse it as a defined outcome (or document and test the
   limit), and state a time expectation in the Javadoc; (h) `ListenerDispatch.subscribe` (101-105) computes the start from the
   local tip and only then registers the subscription, while `moveLocal` runs before the operation's event is emitted after the
   locks are released: a subscription made in that window starts at the new tip, never receives that commit and is not
   corrected by a later event: register first and compute the start after, or take the guard, tested with a pause; (i) a
   repair after INCOMPLETE returns `Release v<n>` on the old release commit even when the working branch received newer saves
   in between (`DocumentRelease.java` 364), as specified ("instead of merging again") but invisible to the caller: report in a
   result detail that the working branch moved on, tested; (j) the numbered release copies the entry's paths from the
   collection branch onto `main` (`DocumentRelease.write` and `entryEdits`, 270-285 and 328-345) instead of merging in core, so a
   change made on `main` to the entry's paths since is overwritten without a conflict, while GDR-STORE-6 says "path-level merge
   in core": decide merge against copy, fix the code or the wording, tested; (k) the GDR-NFR-1 measurement:
   `IndexFootprintTest` (142) asserts only `retained < 16 MiB` from `GraphLayout.parseInstance(index).totalSize()`, which walks
   index, repository and `DfsPackFile` into the JVM-wide `DfsBlockCache` (blocks of earlier tests in the same Surefire JVM
   inflate it), has no lower-bound check that JOL reached the entries, indexes only `main` and not the working-branch
   duplication of a real deployment, and `general.adoc` says "retained" while the test measures reachable: exclude the
   repository and the cache from the walk, add a lower-bound sanity assertion, index `main` plus a working branch, and state in
   `general.adoc` that the figure is relative to the consumer's contributor value type; (l) `ReleaseTest` lacks the cases (a)
   to (c), (i) and (j) name, a tag refused because it exists remotely (same and other commit), and two concurrent releases of
   one document counting `v<n>`: add them; (m) documents and wording: the GDR-STORE-6 bullet "`main` and the tag must be
   pushed together, `main` with lease" (`git-store.adoc` 380) contradicts git-core's one reference per push, which the landing's
   own prose accommodates (`INCOMPLETE`, repair): amend the requirement in place (`main` first with the lease, then the tag, the
   incomplete state and its repair; requirement numbers stay without gap) and the save-sequence diagram if it shows it; the
   `AGENTS.md` "Current state" omits `StoreClone` and `CloneOpening`; the `git-store` `module-info` Javadoc still describes only
   the registry, the configuration and the codec; and `getSimpleName()` of an anonymous or lambda exception class is empty, so a
   step detail reads `<target>: step: ` with no class: use `getName()`.
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
- `git-core` stays read-only (PLAN-08 and PLAN-09 each edited it against their outline); an item that needs a `git-core`
  change is reported in the PR and the inbox message, not absorbed. `git-core`'s one-reference-per-push is a constraint of this
  plan, not a defect of it.
- Review lessons of PLAN-08 and PLAN-09 (`landings/PLAN-08.md`, `landings/PLAN-09.md`) bind every fix: for every reference
  operation that reads and then writes or deletes, name the locks and the readers between the check and the compare-and-swap and
  test each with a pause point; capture identifiers once and re-apply them on a retry; derive remote state from the fetch
  result; a recovery or repair guard identifies its target by data only the guarded operation can produce; a boundary that event
  delivery depends on is captured while the lock is held; a callback never blocks on a lock another callback may hold; never
  attach a throwable to a log record on a sanitised path, DEBUG included; a reference name or path contains the document id, so
  log only the alias and the outcome; a test asserts content and file modes, takes fixture values that differ by construction,
  and ends a concurrent scenario with one deadline, cancellation and `shutdownNow` with `awaitTermination`; one throwing call
  per `assertThrows`; JGit returns are nullable; a Sonar S6539 fix lowers the count of every class it touches.

## Done when

- `verify` passes on the reactor at the plan's head, and every behaviour fix of deliverables 1 to 3 has a test that fails at
  `4a5eed5f593b4624f7b350358d289168ba1f9ae0` (`main` after PLAN-09; the PLAN-07 and PLAN-08 defects are present since
  `a558afd` and `c1e02fc`, the PLAN-09 ones since `4a5eed5`) and passes at the plan's head. An item whose premise the re-read at
  outline refutes is dropped from its deliverable.
- Every item of deliverables 1 to 3 is either fixed and tested at the plan's head, or recorded as an accepted exception or an
  owner decision with its reason in `doc/specification/git-store.adoc`; no document lists an outcome the code does not return.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is
  `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `StoreRepository.toString` renders a remote through `new URIish(remote).setUser(null).setPass(null).toString()`, which keeps
  the path and the query, so a token in either is printed — read at `git-store/src/main/java/de/cuioss/git/document/store/StoreRepository.java`
  70-84 at `a558afd`.
  - verdict: corroborated | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreRepository.java 66-78 re-read at c1e02fc: toString renders withoutUserInformation(remote) = new URIish(remote).setUser(null).setPass(null).toString(), which keeps path and query; the file is unchanged since a558afd (empty git diff e0a8c5b c1e02fc)
- OBSERVED: `StoreProviders.unique` calls `idOf.apply(candidate)` without a guard, and `StoreProviders.load` instantiates every
  `AnchorProvider` through `loadAll` before it reads `anchor.provider` — read at
  `git-store/src/main/java/de/cuioss/git/document/store/StoreProviders.java` 78-90, 158-175 at `a558afd`.
  - verdict: corroborated | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreProviders.java re-read at c1e02fc: load calls loadAll(AnchorProvider.class) at 83 before anchor.provider is read, unique calls idOf.apply(candidate) at 163 unguarded; the file is unchanged since a558afd
- OBSERVED: `StoreConfiguration.anchorKeyIsSigningKey` compares `signed.key()` and `signing.key()` with `String.equals` — read at
  `git-store/src/main/java/de/cuioss/git/document/store/StoreConfiguration.java` 164-168 at `a558afd` (174-177 at `c1e02fc`).
  - verdict: corroborated | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreConfiguration.java 174-177 re-read at c1e02fc: anchorKeyIsSigningKey compares anchorKey.equals(signing.key()) && anchorKeySource.equals(signing.keySource()) as strings (moved from 164-168 by PLAN-08)
- OBSERVED: `CodecTest` holds the only `Codec` implementation of the module's tests (`KeyValueCodec`, a private nested class) and
  Surefire runs with `useModulePath=false` — read at `git-store/src/test/java/de/cuioss/git/document/store/CodecTest.java` 125-130
  and `git-store/pom.xml` 100 at `a558afd`.
  - verdict: corroborated | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: CodecTest.java 127 re-read at c1e02fc: KeyValueCodec is a private static nested class of the test and the only Codec implementation; git-store/pom.xml 100 sets useModulePath=false; both unchanged since a558afd
- OBSERVED: the PLAN-08 leftovers of deliverable 8 at `c1e02fc`: `RepositoryGuard.java` 45-57 (the `GUARDS` and `drafts` maps,
  "never removed"); `ExplicitSave.java` 202-204 (`NOT_FOUND` for an `expectedBlobIds` value that is not an object id) and
  232-240 (the attempt loop, whose debug record says "lost its lease or was rejected"); `DraftExpiry.java` 201 and
  `DraftReferences.java` 233 (`walk.parseCommit` on the tip of a draft reference); `DraftExpiry.java` 222-226 (`gc` with
  `pruneExpire=now` under the in-process guard); `StoreConfiguration.java` 194 (`@SuppressWarnings("java:S4449")`);
  `ConcurrentWritersTest.java` 237-262 (outcome-only assertions); a search of `git-store/src/main` finds no caller of
  `StoreReferences.collections`.
  - verdict: corroborated | checked_at: 4a5eed5f593b4624f7b350358d289168ba1f9ae0 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 4a5eed5: RepositoryGuard.java 48-58 (GUARDS and drafts maps, 'never removed'); ExplicitSave.java 232-233 (NOT_FOUND for a malformed expected blob id) and 270 (attempt loop); DraftExpiry.java 201 and DraftReferences.java 233 (parseCommit on the ref tip, files unchanged); DraftExpiry.java 226 (gc); StoreConfiguration.java 194 (S4449 suppression, unchanged); ConcurrentWritersTest.java 238 (unchanged); StoreReferences.collections now has one main caller, StoreIndex.java 184, and none in the writer path
- OBSERVED: the PLAN-09 leftovers of deliverable 3 at `4a5eed5`: `DocumentRelease.java` 223 (the local tag is created before the
  push of `main`), 259-265 (`pushTag` ends every not-ok push as INCOMPLETE), 270-285 and 328-355 (the numbered release copies the
  entry's paths, `mode` maps a gitlink to a regular file and the source is read as a blob); `JGitRepositoryOperations.java`
  872-876 (an up-to-date push against a lease that does not equal the advertised id is `LEASE_REFUSED`); `StoreEvents.java` 179
  and four more sites that catch `RuntimeException` only; `ExplicitSave.java` 597 (`new TreeEdit.Put(path, content)`);
  `StoreIndex.java` 296-320 (the refresh reads every source and publishes nothing on an `IOException`); `RepositoryGuard.java`
  89-102 and `DraftExpiry.java` 129 (the shared and the exclusive side of one `ReentrantReadWriteLock`); `ListenerDispatch.java`
  101-105 (the start is computed before the subscription is added); `IndexFootprintTest.java` 142 (`GraphLayout.parseInstance`).
  - verdict: corroborated | checked_at: 4a5eed5f593b4624f7b350358d289168ba1f9ae0 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 4a5eed5: DocumentRelease.java 215-265 (tag created before the push of main; pushTag), 270-285 and 328-355 (numbered release copies paths, mode maps a gitlink to REGULAR_FILE, source opened as OBJ_BLOB); JGitRepositoryOperations.java 859-887 (an up-to-date push against a lease different from the advertised id is LEASE_REFUSED); StoreEvents.java 172-182 (catch RuntimeException only); ExplicitSave.java 597 (new TreeEdit.Put(path, content)); StoreIndex.java 296-320 (refresh publishes only after reading every source); RepositoryGuard.java 89-102 and DraftExpiry.java 129 (readLock vs writeLock of one ReentrantReadWriteLock); ListenerDispatch.java 101-105 (start before add); IndexFootprintTest.java 142 (GraphLayout.parseInstance(index).totalSize())

## Expected Surface

- OBSERVED: `git-store/src/main/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/src/main/java/module-info.java`
- OBSERVED: `git-store/src/test/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/pom.xml`
- HYPOTHESIS: `git-store/doc/LogMessages.adoc` (only if a log message is added)
- OBSERVED: `doc/specification/git-store.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (GDR-NFR-1 wording, the GDR-GEN-3 table if an outcome changes)
- HYPOTHESIS: `AGENTS.md` ("Current state": `StoreClone`, `CloneOpening`, and any public type or method that changes)

## Dependencies and Sequencing

- Three deliverables, under the scope-bloat guard (about six). Deliverables 1 and 2 are the former deliverables 7 and 8 of the
  held-back collection plan PLAN-20, moved here on 2026-11-22 at the operator's word so that `git-store` has one hardening plan
  and PLAN-20 keeps `git-core`, `git-testing` and the build guards; deliverable 3 is PLAN-09's leftovers.
- Collection plan: **hold it back from `next` until the operator releases it.** `analyze` folds further `git-store` leftovers
  (PLAN-10, PLAN-11) into this spec in the same edit as its Expected Surface; the operator releases it by naming it in a `next`
  round. It is queued after PLAN-11, at the end of WS-02.
- Depends on: PLAN-09 (shipped, PR #52, `4a5eed5`) and, because it edits the same files (`ExplicitSave`, `DocumentRelease`,
  `StoreWriter`, `StoreIndex`, `ListenerDispatch`), strictly sequential with PLAN-10 and PLAN-11: release it after PLAN-11, or
  earlier only if the operator accepts the rebase of those two plans onto it.
- Overlaps with: every WS-02 plan — strictly sequential. It shares `AGENTS.md` and `doc/specification/general.adoc` with the
  `git-forms`, `git-directory` and `git-timestamp` plans.
- Adjacent to: `git-core` — consumed, not changed; the loop of `compareAndSwap` for `attempts = Integer.MAX_VALUE` stays PLAN-20
  deliverable 3 (c).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-22-store-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
