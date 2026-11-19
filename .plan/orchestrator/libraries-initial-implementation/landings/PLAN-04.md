# Landing Analysis: PLAN-04 — git-core: in-core writing and compare-and-swap reference updates

epic: libraries-initial-implementation
workstream: WS-01
pr: #35 (https://github.com/cuioss/git-document-repository/pull/35), merged through the merge queue as `c8feb0b` (squash, parent `61ac7e1`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `git-core-incore-write-cas-017`
> after verifying it against git, the CI abstraction, the Sonar read and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git show --stat c8feb0b`, 22 paths, +2808/-68. `git-core` main: `InCoreWriter`, `ReferenceUpdater`, `TreeEdit`, `TreeOperation`,
`CommitMetadata`, `CommitSigning` (new), `JGitRepositoryOperations`, `RepositoryOperations`, `RemotePolicy` (changed); `git-core` tests:
`InCoreWriterTest`, `ReferenceUpdaterTest`, `TestSigner` (new), `JGitRepositoryOperationsTest`, `RecordingTransportProtocol`, `RemotePolicyTest`,
`RepositoryOperationsRecordsTest` (changed); documents `doc/specification/git-core.adoc`, `doc/specification/general.adoc`, `doc/README.adoc`,
`doc/concepts/module-structure.adoc`, `AGENTS.md`, `README.adoc`. No `pom.xml`, no `module-info.java`, no `.github` path, no `git-worktrees` or
`git-testing` path: "adjacent to `git-worktrees`, untouched" holds and no dependency was added. The spec's Expected Surface names every touched path
except the root `README.adoc` (`doc/concepts/module-structure.adoc` is named by the Standing Constraints). `GitOutcome.java` and `LogMessages.adoc`
are untouched (no new INFO/WARN/ERROR identifier).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. In-core writing (GDR-CORE-4) | shipped-as-specified (one escape path, L3-1) | `InCoreWriter.write` 105-146: `DirCache.read(reader, parentTree)` / `newInCore()` (129-131), edits applied through `DirCacheEditor` (182-201), blob, tree and commit inserted through one `ObjectInserter`, author, committer, message and parents from the caller, optional `Signer` through `signObject` (203-210); the snapshot test `onlyObjectsChange` |
| 2. Compare-and-swap (GDR-CORE-5) | shipped-as-specified (the spec text says `IO_FAILURE` is "an exception"; the delivery returns `FAILED`, as the spec document says, L1-1) | `ReferenceUpdater.compareAndSwap` 101-142, `update` 245-252: expected old id, `ObjectId.zeroId()` for a new reference, no force flag, `NEW`/`FAST_FORWARD` success, `LOCK_FAILURE` re-applies up to `attempts`, then `CONFLICT`, every other result `REJECTED` naming it |
| 3. Compare-and-delete | shipped-as-specified | `compareAndDelete` 180-221, `delete` 230-237: expected id plus force, `FORCED` success, after `LOCK_FAILURE` a re-read decides (unchanged: retry with the same expected id; moved or gone: `CONFLICT` at once); `updateRef(refName, true)` so a symbolic reference is deleted itself |
| 4. Tests | shipped-as-specified | `InCoreWriterTest`, `ReferenceUpdaterTest` (in-memory and local fixtures, threaded `concurrentInMemory` and `concurrentLocal`, `deleteRacingNewerDraft`, a signed commit verified by `TestSigner`), `doc/specification/git-core.adoc` state updated |
| 5. Hardening (a)-(i) of PLAN-02 | shipped-modified: (b), (c), (e), (f), (g), (h) fixed, (a), (d), (i) recorded as accepted exceptions; (e) and (i) are thinner than the text says (L3-8, L3-9) | (a) wording corrected, not `RefusingFS.systemReader` made the default (`git-core.adoc` 10-17, 79-83, `doc/README.adoc`); (b) `notWritten` (`JGitRepositoryOperations.java` 436-445); (c) `setTimeout(TRANSPORT_TIMEOUT_SECONDS)` on clone, fetch and push; (d) `git-core.adoc` 167-176; (e) `setUser(null).setPass(null)` (251); (f) `toString` of `FetchCommitInput` and `RemotePolicy`; (g) `failure(e)`; (h) the `push` Javadoc and `git-core.adoc` 291-296; (i) `git-core.adoc` 143-148 and 363-365 |

The lane reports 5 deliverables done, the spec carries 5. The lane's "(a), (d) and (i) are recorded as accepted exceptions" is borne out by the spec text; the owner decision the spec asks for under (a) is not
evidenced in the diff (the PR's Test Plan box "review the three accepted exceptions" is unchecked), and the lane records a different owner decision — "reflog writing turned off, as decided with the owner" — that no deliverable names (L2-2).

Claims of the landing narrative against the diff: the five review fixes (`failure(e)`, `parseAny` in `InCoreWriter`, `updateRef(refName, true)` in `compareAndDelete`, the uppercase-scheme rendering, the digit-prefixed test value) are in the merged code and
tests; `TRANSPORT_TIMEOUT_SECONDS` replaces `FETCH_TIMEOUT_SECONDS` (no stale reference). **Not borne out in full:** the lane's `RevWalk.parseCommit replaced by parseAny` holds for `InCoreWriter` only, `ReferenceUpdater.parseCommit` (239-243) still peels a tag (L2-4).
**Unverified:** "580 tests" — by annotation count `git-core` 159, `git-testing` 53, `git-worktrees` 93 (305; the leaf counted 297); method counts and executed counts can differ (parameterized expansion), so the figure is neither confirmed nor refuted.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `c8feb0b`, and the JGit sources in the local 7.8.0 sources jar. By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: reading merged code against the spec is the depth the dispatch rule exists for, and one landing's read must not share context with the reconciliation of the 16 lessons drained in the same scan.
- **Verdict:** layer 1 — 1 minor (L1-1), 7 of 7 persisted claims corroborated; layer 2 — 4 minor, 1 note, 3 not shown by reading; layer 3 — 1 major (L3-1), 8 minor, 3 info.
- **Re-read by the orchestrator at `c8feb0b`:** `InCoreWriter.java` 95-211 (write, `checkPaths`, `parseCommit`, `applyEdits`, `sign`); `ReferenceUpdater.java` 90-253 (both loops, `delete`, `parseCommit`, `update`); `JGitRepositoryOperations.java` 243-256 (the clone's stored origin), 436-445 (`notWritten`), 700-712 (`readFile`), 1050-1062 (`display`); `git-core.adoc` 203-210, 230, 243-247, 360-366; the `README.adoc` path against the spec's Expected Surface; the `@Test` counts; `git diff 2c728bc 61ac7e1 -- git-core` (empty, so the PLAN-02 state read at outline is the base's state); `~/git/pm-mcp-core/.../JGitOperations.java` 270 at `287e1a86` (the only `setExpectedOldObjectId`, with `ObjectId.zeroId()`; only this file was read); and in the JGit 7.8.0 sources jar `DirCache` (119, 136, 976), `DirCacheBuilder.toEntry` (177-187) and `DirCacheEntry.checkPath` (864-872, throws `dircache.InvalidPathException extends IllegalArgumentException`), `DirCacheEditor.deleteOverlappingSubtree` (172-197), `RefUpdate` (680, 714-718: `isNameConflicting` returns `LOCK_FAILURE`), `Signer.signObject` (66-73), `PushProcess` (140-168: advertisement, then the pre-push hook), `RevWalk.parseCommit` (1133-1139, peels), `GpgConfig` (77, `getEnum`). **Not re-read** (leaf-only, recorded as unverified leads): the per-platform rule set of `ObjectChecker` behind L3-7; the stored-config effect of a query-string token (L3-8); the test bodies behind L2-5 to L2-7; `FetchProcess` setting a non-null `TrackingRefUpdate` result; the `DirCacheBuilder.enterTree` empty-subtree drop (L3-10); JDK `TempFileHelper` for (d).

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-0 | Claim 0: `pm-provider-git` has no `ObjectInserter` write, no `DirCache.read(ObjectReader, ...)` and no retrying update; its only `setExpectedOldObjectId` creates with `zeroId()` | checked | confirms | `JGitOperations.java:270` at `287e1a86` (only that file read) |
| L1-1 | Claim 1: the `RefUpdate` semantics (expected-old before force, `LOCK_FAILURE` on a moved reference, `REJECTED` for a delete without force) match JGit 7.8.0 | checked | confirms | `RefUpdate.java` 680-724 (conflict check, lock, expected compare); the spec's link text not separately re-read |
| L1-2 | Claim 2: `DirCache.read(ObjectReader, AnyObjectId)` and `writeTree(ObjectInserter)` exist in 7.8.0 | checked | confirms | `DirCache.java` 136, 976 (and `newInCore` 119) |
| L1-3 | Claim 3: the `Signer` hook is reachable from an `ObjectInserter`-built commit | checked | confirms | `Signer.java` 66-73 (`signObject` → `setGpgSignature`); `InCoreWriter.java` 203-210 |
| L1-4 | Claim 4: the leftovers of deliverable 5 stand as read at `2c728bc`; claim 6 (verify-first): item (b) had no status check before | checked | confirms | `git diff 2c728bc 61ac7e1 -- git-core` empty; no deliverable-5 item was already absent at the base |
| L1-5 | Claim 5: `PushProcess` runs the pre-push hook after the advertisement | checked | confirms | `PushProcess.java` 140-168 |
| L1-6 | The spec says `IO_FAILURE` ends compare-and-swap "an exception"; `general.adoc` GDR-GEN-3 and `git-core.adoc` GDR-CORE-5 say `FAILED` | checked | refutes | `git-core.adoc` 230, 243, 247; `ReferenceUpdater.java` 129-131, 208-210. The delivery follows the specification document; the spec wording is the error (minor) |
| L1-7 | The Done-when items "`verify` passes at the head" and the Sonar read | not shown by reading | none | the Sonar read is settled in the routing section; `verify` is a run fact |

Persisted onto the spec (`corpus set-verdict`): claims 0-6 corroborated.

**Layer 1 verdict:** findings — 1 minor (L1-6); 1 not shown by reading (L1-7)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `InCoreWriter.write` 105-146 | `rootCommit`, `commitOnParent`, `metadataAsPassed` (author, committer, message, parent order), `onlyObjectsChange` (a snapshot of the files outside `objects/` before and after), `signedCommit` (`TestSigner` verifies the payload), `signingFailure`, `absentParent`, `parentIsNoCommit`, `parentIsAnnotatedTag`, `invalidPutPath`, `invalidDeletePath`, `duplicatePath` | not covered: a file and a directory below it in one call, a parent tree JGit's path check rejects (L3-1), preserved modes | checked |
| 2 | `ReferenceUpdater.compareAndSwap` 101-142 | `createsReference`, `fastForwards`, `lostRaceRetried`, `lastAttemptLost`, `failedWrite`, `symbolicReferenceFollowed`, `attemptsBelowOne`, `concurrentInMemory`, `concurrentLocal` | `neverForces` guards only the expected id (L2-5, lead) | checked |
| 3 | `compareAndDelete` 180-221 | `deletesAtExpectedId`, `deletesSymbolicReferenceOnly`, `deleteRacingNewerDraft`, `referenceGone`, `lockReleasedRetried` (one re-read, same expected id, a deterministic lock release), `lockNeverReleased`, `abbreviatedExpectedId`, `attemptsBelowOne` | none | checked |
| 4 | the two fixtures | threads on the in-memory fixture (all updaters succeed) and the safety invariant on the local one (the lane's own lesson: lock-file contention is reported as `LOCK_FAILURE`); the delete-versus-newer-draft race is sequential and deterministic | none | checked |
| 5 | see the fidelity table | (b) `fetchUpdateNotWritten`, (c) `transportTimeout`, (e) `cloneStoresNoUserInformation`, (f) `toStringRendersOriginOnly`, `fetchCommitInputRendersNoSecret` and the uppercase-scheme variant, (g) `anonymousFailureNamed`, `emptyPathNamed`; (a), (d), (h), (i) are text only | none | checked, with the residue in L3-8, L3-9 |

Layer-2 findings:

- **L2-1 (minor).** The `REJECTED` branch of `notWritten` (440-442, four results) and the `IO_FAILURE`/default branch are untested: the leaf reads `fetchUpdateNotWritten` as covering `LOCK_FAILURE` only (test body not re-read; the code at 436-445 was re-read). Whether `TrackingRefUpdate.getResult()` can be `null` there (an NPE in the `switch`) was not traced to the end.
- **L2-2 (minor).** "Reflog writing turned off, as decided with the owner" is implemented (`disableRefLog()` at `ReferenceUpdater.java` 235 and 250) and documented, but no deliverable names it, the decision is not evidenced in the diff, and no test asserts the absence of a reflog (re-read: `ReflogReader` occurs in `ReferenceUpdaterTest` only inside a test double at 665-666), so a mutation that re-enables the reflog passes.
- **L2-3 (minor).** The root `README.adoc` (3 lines) is in no declared surface entry; it is a consistent state-wording update (the lane's lesson 003 and the planning finding 04f7cd, resolved `taken_into_account` without a surface sync, lesson `2026-11-19-14-003`).
- **L2-4 (minor).** `ReferenceUpdater.parseCommit` (239-243) calls `RevWalk.parseCommit`, which peels an annotated tag (`RevWalk.java` 1133-1139): a reference that points at an annotated tag is read as its commit, the commit becomes the parent and the update, which expects the tag id, ends `REJECTED`, not `NOT_FOUND`. The behaviour is safe but contradicts the narrative claim, which holds for `InCoreWriter` only.
- **L2-5 (lead, not re-read).** `neverForces` (test line 245) cannot detect a mutation that sets the force flag, because JGit compares the expected id before the force flag; it only guards the expected id. Test body not re-read.
- **L2-6 (note).** `concurrentLocal` (362) asserts the safety invariant only and would pass with zero successes — by the lane's own decision (lesson message 001), not a defect.
- **L2-7 (lead, not re-read).** `Map.of` over three randomly generated names in `InCoreWriterTest` (rootCommit, a second test) throws on a duplicate key: a tiny, non-zero flake risk.
- **L2-8 (not shown by reading).** "580 tests" and "`verify -Ppre-commit` passed" (see the fidelity section); the owner decision under item (a); JDK `TempFileHelper` behind the accepted exception (d).

**Layer 2 verdict:** findings — 4 minor (L2-1 to L2-4), 2 leads (L2-5, L2-7), 1 note (L2-6); 3 not shown by reading (L2-8)

### Layer 3 — Correct

One major and eight minor defects, and no blocker. The code is careful in the large: resources are closed (`ObjectReader`, `RevWalk`, `ObjectInserter` in try-with-resources), the expected id precedes the force flag, the delete re-reads instead of inferring a move, a symbolic reference is deleted itself, details name a class or a field and never a caller string, and `attempts < 1` is a documented programming error. The defects are at the edges again — the same pattern as PLAN-19's. Re-read by the orchestrator at `c8feb0b`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `InCoreWriter.java:129-131` (`DirCache.read(reader, parentTree)`), catch at 143 (`IOException \| GitAPIException \| JGitInternalException`); JGit 7.8.0 `DirCacheBuilder.toEntry` 183 → `DirCacheEntry(byte[], int)` → `checkPath` 864-872 | a parent commit whose tree holds an entry that `SystemReader.checkPath` rejects (`.GIT`, `git~1`; on a Windows host a backslash, a device name or a trailing dot), which reaches a mirrored clone through a push that skipped fsck | `dircache.InvalidPathException` (an `IllegalArgumentException`) escapes `write` and `compareAndSwap` (which calls it on every attempt) instead of ending as a `FAILED` result (GDR-GEN-3); the reference can never be moved through the library. Not tested | major |
| L3-2 | `InCoreWriter.java:207` (`new GpgConfig(repository.getConfig())`, which reads `gpg.format` through `Config.getEnum`), `ReferenceUpdater.java:115` (`operation.apply`), `InCoreWriter.java:110` (`List.copyOf(edits)`) | a `Signer` or a `TreeOperation` that throws a `RuntimeException`, `gpg.format = bogus`, an `operation` returning `null` or a list with a `null` element | the exception propagates out of `write` and `compareAndSwap`; the Javadoc lists only `NullPointerException` for the arguments and `IllegalArgumentException` for `attempts`. Nothing is leaked or left open | minor |
| L3-3 | `ReferenceUpdater.java:111` and `193` (`for (int attempt = 1; attempt <= attempts; attempt++)`) | `attempts = Integer.MAX_VALUE` with a persistent `LOCK_FAILURE` (for example a stale `.lock` file) | `attempt` overflows to `Integer.MIN_VALUE`, which is `<= attempts`: the loop never ends and the `CONFLICT` result is never produced; `compareAndSwap` writes a commit per iteration with no back-off. No upper bound is documented | minor |
| L3-4 | `ReferenceUpdater.java:122` (`update`) with `RefUpdate.java:714-718` | `refName = refs/heads/a/b` while `refs/heads/a` exists (a name conflict on a reference that does not exist) | JGit answers `LOCK_FAILURE`, which `compareAndSwap` treats as a lost race: the operation runs and a commit is written on all `attempts` attempts, then `CONFLICT` "LOCK_FAILURE on every attempt". The caller cannot tell a name conflict from contention (`compareAndDelete` is not affected) | minor |
| L3-5 | `ReferenceUpdater.java:112, 247` and `compareAndDelete` 194: no `Repository.isValidRefName` call | a `refName` such as `refs/heads/../../../outside/x` that does not exist (non-existent, so `exactRef` reads `null`) | JGit's `RefDirectory` maps the name to a file (the `fileFor` non-validation was read in the PLAN-19 round, the leaf read `newUpdate` at 627-641 here, not re-read); the `LockFile` creates the parent directories and a 40-hex file outside the repository. The Javadoc says "the full name", a caller precondition; it matters when a branch name derives from user input (the store's draft references) | minor |
| L3-6 | `InCoreWriter.java:151-164` (`checkPaths` refuses only equal strings) with JGit `DirCacheEditor.deleteOverlappingSubtree` 172-197 | `Put("a")` and `Put("a/b")` in one call | the editor removes the file `a` when `a/b` is added, so the put of `a` is lost silently whatever the list order; `TreeEdit.Put`'s Javadoc ("a file or a directory at the path is replaced") holds for an existing parent tree only | minor |
| L3-7 | `InCoreWriter.java:155` (`SystemReader.getInstance().checkPath`) | the same edit path on a Linux, a Windows and a macOS host, or under an installed `SystemReader` | validation is platform-dependent: the leaf reads `ObjectChecker` as accepting a backslash, a reserved Windows name, a trailing dot and case-fold duplicates on Linux (**not re-read**), so the same edit is `NOT_FOUND` on one host and a commit on another. An invalid path is classed `NOT_FOUND` where git-worktrees uses `REFUSED_BY_POLICY` for an invalid name — specification-conform, odd | minor |
| L3-8 | `JGitRepositoryOperations.java:251` (`new URIish(input.url()).setUser(null).setPass(null)`) with `display` 1055-1062 | a token in the query string (`https://host/r.git?access_token=...`) or an uppercase-scheme URL, which `URIish` parses as a local path so that `setUser(null)` does nothing | the stored origin keeps what the two calls do not remove (the leaf's reading of `URIish`; the stored-config effect was **not re-read**); the GDR-NFR-3 table says tokens are never stored, and `display` strips them for logs and results only. Item (e) says "user information" and the fix is that | minor |
| L3-9 | `JGitRepositoryOperations.java:708` (`content.readAllBytes()`); accepted exception at `git-core.adoc` 363-365 | a large pinned blob, or a policy that admits a whole host | `OutOfMemoryError`, an `Error`, not a result. The recorded reason ("a source the remote policy admitted") is weak: a policy admitting `https://github.com` admits every repository there | minor |
| L3-10 | `InCoreWriter.java:129` (`DirCache.read` skips empty subtrees), 191 (`Put` always sets mode `100644`), 111-113 (parent ids are checked for format only) | a parent tree that holds an empty tree, an executable file or a symlink; two equal parent ids | the commit's tree id differs from the parent's even with no edits, an executable or symlink is reset to a regular file by a `Put`, a commit with a repeated parent is written. Documented for `Put`; the empty-tree drop and the duplicate parents are not (the empty-subtree drop is the leaf's reading, **not re-read**) | info |
| L3-11 | `ReferenceUpdater.java:235, 250` (`disableRefLog`) | updating the checked-out branch of a non-bare repository | no reflog, and the working tree and index stay as they were; the Javadoc does not warn. The module is for bare repositories | info |
| L3-12 | `ReferenceUpdater.java:127, 205` (DEBUG logs), details | a reference name that embeds a person-bound identifier (the store's draft references) | the name reaches details and DEBUG logs; not a defect of the code as written, a point for the store plans (GDR-NFR-3) | info |

**Documented by design, not a defect:** a signing cancellation is reported as `FAILED`; the module declares `attempts` below 1 a programming error.

**Layer 3 not shown by reading:** whether the whole suite and the 580 tests pass; whether `FetchProcess` always sets a non-null result on a `TrackingRefUpdate` (the `switch` at `notWritten` 439); the platform-dependent path rules (L3-7); JGit's behaviour for a `refName` outside `refs/` beyond the `fileFor` read of the PLAN-19 round.

**Layer 3 verdict:** findings — 1 major (L3-1); 8 minor (L3-2 to L3-9); 3 info (L3-10 to L3-12)

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| `general.adoc` GDR-GEN-3 | Expected outcomes are results, not exceptions | deviates | `InvalidPathException` from `DirCache.read` (L3-1) and the unchecked exceptions of a `Signer`, `GpgConfig` or `TreeOperation` (L3-2) escape; every other outcome is a result, `attempts < 1` is a documented programming error |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | new types are in the existing `@NullMarked` package; `@Nullable` on the signing parameters and the locals in `ReferenceUpdater` 113-114; `package-info.java` not in the diff and not re-read |
| `general.adoc` GDR-GEN-4 / cui-logging | `CuiLogger`, `LogRecord` holder, catalogue for every INFO/WARN/ERROR | conforms | `ReferenceUpdater` adds two DEBUG calls with plain format strings (127, 205), no new INFO/WARN/ERROR identifier, so `LogMessages.adoc` needs no entry |
| AGENTS.md | Javadoc on every public type and method | conforms | the new types and the changed `RepositoryOperations` methods are documented; undocumented behaviours are the findings above |
| AGENTS.md | No dependency without approval | conforms | no `pom.xml` in the diff |
| AGENTS.md | Never touch `.github/project.yml` | conforms | no `.github` path in the diff |
| `general.adoc` GDR-NFR-3 | No secret in results, logs or storage; tokens are never stored | deviates | details and logs are clean (a class, a field name, a `RefUpdate.Result`); the stored origin keeps a query-string token and an uppercase-scheme credential (L3-8, leaf reading) |
| `git-core.adoc` GDR-CORE-1 | The library never starts a process | conforms | the diff adds no process start; signing runs a caller-supplied `Signer` only |
| AGENTS.md | Tests: EasyMock for test doubles | deviates (reasoned) | `TestSigner`, the lock-releasing repository double and `RecordingTransportProtocol` are hand-written and no `git-core` test uses EasyMock (`grep` over `git-core/src/test`: none); they delegate to real JGit objects, so the impact is low |
| CLAUDE.md | Verify JGit claims and name the version | conforms | 7.8.0 named in Javadoc and spec; the claims re-read here hold (`DirCache`, `Signer.signObject`, `RefUpdate`, `PushProcess`, `RevWalk.parseCommit`, `DirCacheEditor`) |

## Metrics and Anomalies

- Tokens: 13,633,697 (the lane's figure, not re-verified). The 5-execute boundary row was stamped `--total-tokens 743` for 742996 and cannot be corrected (lesson `2026-11-19-14-002`); the retrospective found the typed phase figures about 388K short of the boundary rows.
- Duration: 93,152 s (about 25.9 h wall clock, of which a long hold for the operator's word to merge).
- Anomalies: `effort` was never stamped (`unknown`) and the three review numbers are `unknown`, so `inbox landing-check` reports `complete: false` (missing `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`). `create-pr` ran inline instead of through a dispatch. Every dispatch-boundary row (11) is keyless (lesson `2026-11-18-07-006`, fourth plan). `manage-change-ledger append --changed-paths-file` failed in two envelopes (lesson `2026-11-18-13-004`, fifth plan). A Q-Gate under-coverage finding was closed without syncing the Affected files (lesson `2026-11-19-14-003`). An invented `qgate query` verb, `get-deliverable --number` and `--termination-cause completed` were rejected once each (lessons `2026-11-18-07-002`, `2026-11-19-06-001`, `2026-11-19-14-001`). The execution manifest carried `final_merge_without_asking=true`, which contradicts the AGENTS.md rule "do not merge unless the user says so"; the lane held the merge until the owner approved in chat and did not change the manifest value (Watch).

## Routing and Merge Behavior

- Review: the lane states 5 CodeRabbit inline threads of #35 were replied to and resolved at commit `2c2c8e0`, and CodeRabbit refused further review on quota afterwards. **Provider re-read:** `review-gate` for #35 returns `unresolved_review_threads: 0`, `review_body_findings_total: 5`, `review_body_findings_handled: 0`, `merge_allowed: false`, `body_findings_undisposed` (five inline findings, all CodeRabbit). The landing's three review numbers are `unknown`, so there is no difference to record as a false claim; the lane's statement (five threads resolved) agrees with the 0 unresolved; `handled: 0` counts dispositions the provider does not carry, so `review_body_findings_handled` is **not re-verified** (the progress file was not read).
- CI/merge: merged through the merge queue as a squash, `c8feb0b`; the CI abstraction reads state `merged`, merge commit `c8feb0bb...`. The merge was held for the owner's word (see the anomalies).
- Sonar: the landing claims `sonar_open_new_issues=0`, measured at the fix commit and not re-read after the merge. **Re-read:** the quality gate of #35 is `OK` (new coverage 87.3 %, duplicated lines 0.0, hotspots 100 % reviewed); the gate status is a verdict, not a count of open issues, so the count is recorded as not re-verified.
- Overlap with the parallel PLAN-19: PLAN-19 landed first (`e2aced9`) and touched `doc/specification/general.adoc`, `AGENTS.md` and `doc/README.adoc`; PLAN-04's branch was rebased onto it and the lane reports that **four documentation files conflicted and were merged by hand** (`doc/README.adoc`, `AGENTS.md`, `README.adoc`, `doc/specification/general.adoc`), keeping both sides. The collision is the "Current state" paragraph and the "implemented" lists, the one the pairing gate put to the operator, who accepted it. It was foreseen for two files (`AGENTS.md`, `doc/README.adoc`) and for `general.adoc` (the Watch); `README.adoc` was in neither spec's surface.

## Reconciliation Actions

- [x] row `status` -> `shipped` — `orchestrator queue --transition PLAN-04 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-04 --field pr --value 35`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-04 --field landing --value landings/PLAN-04.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-04 --field plan_marshall_plan_id --value git-core-incore-write-cas`
- [x] epic.md narrative reconciled (PLAN-04 shipped; the lesson dispositions; the layer-3 report and the collection plan; the incomplete landings; the `general.adoc` Watch retired; the manifest Watch)
- [x] layer-1 verdicts persisted onto the spec (claims 0-6 corroborated)
- [x] PLAN-05 folded the four review rules (one Standing Constraints bullet, no surface change)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-ups

- The layer-3 defects L3-1 to L3-9 above are reported to the operator; the operator asked for them to be collected into one plan with the other leftovers (PLAN-20, held back from `next` until released).
- PLAN-05 (`git-core` merge, reading and notes) is the next foundation plan and follows PLAN-04 in the same package.
