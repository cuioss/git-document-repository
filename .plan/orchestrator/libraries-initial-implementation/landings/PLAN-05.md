# Landing Analysis: PLAN-05 — git-core: in-core merge, reading and git notes

epic: libraries-initial-implementation
workstream: WS-01
pr: #37 (https://github.com/cuioss/git-document-repository/pull/37), merged through the merge queue as `14269fe` (squash, parent `5f04efc`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-05-git-core-merge-read-notes-013`
> after verifying it against git, the CI abstraction, the Sonar read and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git show --stat 14269fe`, 25 paths, +5030/-113. `git-core` main: `GitNotes`, `InCoreMerger`, `MergeResult`, `RepositoryReader` (new), `InCoreWriter`, `ReferenceUpdater`,
`JGitRepositoryOperations`, `module-info.java` (a Javadoc comment only) and `git-core/pom.xml` (changed); `git-core` tests: `GitNotesTest`, `GitOutcomeSpecificationTest`, `InCoreMergerTest`,
`InterceptingTransportProtocol`, `MergeResultTest`, `RedirectEndToEndTest`, `RepositoryReaderTest` (new), `AbsenceContractsTest`, `InCoreWriterTest`, `JGitRepositoryOperationsTest`,
`ReferenceUpdaterTest` (changed); documents `doc/specification/git-core.adoc`, `doc/specification/general.adoc`, `doc/README.adoc`, `doc/concepts/module-structure.adoc`, `AGENTS.md`.
No `.github` path, no other module and no `GitOutcome.java` is touched. Of the spec's Expected Surface every touched entry is declared except `general.adoc` (the dependency-approval record and the
`MergeResult` rows of the GDR-GEN-3 table), `doc/concepts/module-structure.adoc` (the State column, which the Standing Constraints name) and `module-info.java` (a comment): all three are explained.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. In-core merge (GDR-CORE-6) | shipped-modified (the conflict carrier is a sealed `MergeResult`, an owner decision at the outline) | `InCoreMerger.merge` (108-145): `MergeStrategy.SIMPLE_TWO_WAY_IN_CORE.newMerger`, success yields the tree id in `MergeResult.Merged`, on failure the conflicting paths are computed by a `NameConflictTreeWalk` over base and both tips (161-191) with the merger's own rule (id or mode differing from the base on both sides and between the sides, 176-181), no commit on conflict, `RECURSIVE` never used |
| 2. Reading (GDR-CORE-9) | shipped-as-specified | `RepositoryReader.snapshot`, `history` (unsimplified ancestry, each commit compared with its parents, `walk.next()` driven by hand, 269-285), `changes` (added, modified, deleted between two commits) |
| 3. Notes (GDR-CORE-10) | shipped-modified (the update goes through a package-private `ReferenceUpdater.compareAndSwapTree`, a step the spec text did not name) | `GitNotes.read` and `write` through `NoteMap.readTree` / `set` / `writeTree`; each attempt re-reads the tip's notes tree and re-sets the note, so another writer's note is never lost |
| 4. Tests | shipped-as-specified | `InCoreMergerTest`, `MergeResultTest`, `RepositoryReaderTest` (in-memory fixture), `GitNotesTest` (`safetyOnLocal`, `livenessInMemory`), `doc/specification/git-core.adoc` and `AGENTS.md` state updated |
| 5. Residuals of PLAN-02 (a)-(f) | shipped-as-specified | (a) `GitOutcomeSpecificationTest.tableMatchesEnum` ties the 20 names to the 20 GDR-GEN-3 rows in order (names only, not the other cells); (b) `AbsenceContractsTest` rebuilt on real operations (the old "a deleted ref has no new id" and the no-lease and no-credentials cases were dropped without a replacement); (c) recorded with JGit 7.8.0 links and three tests (`remotePreReceiveHookNeverRun`, `receivePackProgramNeverRun`, `uploadPackProgramRefused`); (d) `RedirectEndToEndTest` against an HTTPS MockWebServer; (e) recorded as an accepted exception and pinned by `extraHeaderAndCookieFileReachRemote`; (f) `publishLosesRace`, `mirroredRefsNotWritten`, `refusedRedirectLogged` |

The lane reports 6 deliverables done, the spec carries 5 — the outline split them after the operator redesigned two of its recommendations (the redirect test's server and the conflict carrier;
lesson `2026-11-19-19-003`), so the number is not a mismatch. The new test dependency `de.cuioss.test:cui-test-mockwebserver-junit5` is in test scope only (`git-core/pom.xml` 67-72), adds no
`requires` to `module-info.java` and nothing to a consumer's classpath; the approval is recorded in `general.adoc` (the dependency sentence, "on 2026-11-19") and `git-core.adoc` (item 5 d), which the lane
wrote. The spec's rule — the choice goes to the owner through the question tool BEFORE any library is added — is borne out by the lane's own lesson (the operator answered "why not cuihttpmock" at the outline
question), not by an independent decision record in the diff.

Claims of the landing message and the lane's lessons against the diff: the unsimplified-ancestry fix, `walk.next()`, the corrected `writeTree` Javadoc, the whitespace-name fix in `MergeResult.Conflicting`, the DFS symbolic-reference
fallback and the three round-2 fixes are all in the merged code. **Not borne out in full:** "the read-ahead and `RevWalkException` defect of a for-each over `RevWalk` was fixed" holds for `RepositoryReader.history` only; the sibling
`JGitRepositoryOperations.log`, which the plan reworked, still has it (L3-2). **Unverified:** the three firings of ci-verify, automatic-review and sonar-roundtrip, the 22 Sonar fixes, "fixtures distinct by
construction" (the leaf reads a generator in `TestCommits`), "the whole `http.*` redirect group" (the test pins `http.sslVerify`, `followRedirects` and `maxRedirects`; that this is the whole group that can
change the outcome is not shown) and any test count.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `14269fe`, and the JGit sources in the local 7.8.0 sources jar. By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: reading merged code against the spec is the depth the dispatch rule exists for, and one landing's read must not share context with the reconciliation of the 12 lessons drained in the same scan.
- **Verdict:** layer 1 — all six claims corroborated, 1 gap resolved by the delivery; layer 2 — 3 minor, 2 not shown by reading; layer 3 — no blocker, no major, 4 minor re-read and 3 minor leads; all ten PLAN-20 defects of PLAN-04's code (P1-P10) are still present.
- **Re-read by the orchestrator at `14269fe`:** `InCoreMerger.java` 108-145; `JGitRepositoryOperations.java` 468-490 (`log`); `ReferenceUpdater.java` 195-226 (`swap`), 281-285 (`compareAndDelete`), 330-340 (`parseCommit`), 372-390 (`unresolvedUnderLock`) and a search for `isValidRefName` (none); `GitNotes.java` 104-122 and 176-210 (`read`, `write`, `isNotesRef`); `git-core/pom.xml` and `module-info.java` (the diff); `general.adoc` 29-41 and `git-core.adoc` 150-154 (the approval record); `git show 2c728bc` for `GitOutcome` (20 constants), `general.adoc` (20 rows), `AbsenceContractsTest` (two `assertNull` on values just passed in) and the two named test lines; and in the JGit 7.8.0 sources jar `StrategySimpleTwoWayInCore.add` (148-165, `new DirCacheEntry(tw.getRawPath(), stage)`), `RevWalkException` (a `RuntimeException`), `RefDirectoryUpdate.tryLock` (46-60, returns false before `setOldObjectId`), `NoteMap` (45, 84, 134, 277, 332, 347), `ReceivePack` (Java hooks only; native hooks only through `GitHook` / `FS.runHookIfPresent`). **Not re-read** (leaf-only, recorded as unverified leads): the per-item test traces of 5 (a) to (f), `RepositoryReader` and `GitNotes` beyond the lines above, the guarded-batch design against `ReftableBatchRefUpdate` and `PackedBatchRefUpdate` (L3-5, L3-6), the `LargeObjectException` reach (L3-8), the whole-ancestry cost of `history` (L3-7), and the layer-3 info points.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-0 | Claim 0: `pm-provider-git` has no `Merger`, `MergeStrategy`, `NoteMap` or `DiffEntry` | checked | confirms | the leaf read the provider sources on disk at `287e1a86`; none of the four names occurs |
| L1-1 | Claim 1: `StrategySimpleTwoWayInCore` reports a path modified by both sides as a conflict and reports no conflicting paths | checked | confirms | JGit 7.8.0 `StrategySimpleTwoWayInCore.java` 95-146 sets a boolean only (the leaf's reading; the `add` method at 148-165 re-read) |
| L1-2 | Claim 2: `NoteMap.read`, `set`, `writeTree` have the needed signatures in 7.8.0 | checked | confirms | `NoteMap.java` 84, 134, 277, 347 (and `newEmptyMap` 45) |
| L1-3 | Claim 3: the residuals of deliverable 5 stand as read at `2c728bc` | checked | confirms | `2c728bc`: the redirect test and the local `pre-push` hook test exist, `AbsenceContractsTest` asserts on values it just passed in, `GitOutcome` has 20 constants and the table 20 rows |
| L1-4 | Claim 4: JGit 7.8.0 `ReceivePack` does not run native hooks | checked | confirms | `ReceivePack.java` 253, 300, 2146 hold the Java `PreReceiveHook` only; native hooks run through `GitHook` / `FS.runHookIfPresent` (callers `hooks/GitHook`, `FS`, `FS_POSIX`, `FS_Win32_Cygwin`) |
| L1-5 | Claim 5, verify-first: no OBSERVED claim of deliverable 5 was refuted | checked | confirms | L1-3 and L1-0 hold, none had to be dropped |
| L1-6 | The spec says "on failure the conflicting paths are computed", and GDR-GEN-3 says a `GitResult` carries a value on success only: the carrier was an open design question | checked | refutes | settled by the sealed `MergeResult`, recorded in `general.adoc` and `git-core.adoc`; the spec's path-computation, base-and-both-tips walk, no-commit and no-`RECURSIVE` requirements are still met (`InCoreMerger.java` 161-191, test `writesNoCommit`). Info |
| L1-7 | The Done-when items "`verify` passes at the head" and the Sonar read | not shown by reading | none | the Sonar read is settled in the routing section |

Persisted onto the spec (`corpus set-verdict`): claims 0-5 corroborated.

**Layer 1 verdict:** findings — 1 info (L1-6); 1 not shown by reading (L1-7)

### Layer 2 — Implemented

Each of the five deliverables traces to code and to a test that can fail (the table above; the per-test traces are the leaf's reading, not re-read). Layer-2 findings:

- **L2-1 (minor).** The new test dependency is recorded as approved by the lane's own text (`general.adoc`, `git-core.adoc`); no independent decision record (an ADR, a commit trailer) is in the diff, so the owner's approval is not shown by reading (the lane's lesson names the operator's answer).
- **L2-2 (minor).** The lane's "read-ahead / `RevWalkException` fixed" is partly borne out: `JGitRepositoryOperations.log` still reads one commit past `maxCount` (L3-2).
- **L2-3 (minor).** The new test doubles (`Proxy`-based recording connection factory, lambdas, a fake `Transport`) are hand-written where AGENTS.md prescribes EasyMock, and `git-core/pom.xml` declares no EasyMock although `general.adoc` lists it as approved (the same pattern as PLAN-04, pre-existing).
- **L2-4 (note).** 5 (a) ties the constant names to the table's first column in order, not the outcome and raised-by cells; 5 (b) dropped two absence cases without a replacement (a deleted reference has no new id, no lease, no credentials). `InterceptingTransportProtocol` registers with the JVM-wide `Transport.register`; whether JUnit parallelism is off was not read.
- **L2-5 (not shown by reading).** The test counts, the Sonar fixes, the three firings, "fixtures distinct by construction", and "the whole `http.*` group".

**Layer 2 verdict:** findings — 3 minor (L2-1 to L2-3), 1 note (L2-4); 1 not shown by reading (L2-5)

### Layer 3 — Correct

No blocker and no major defect. The code is careful in the large: results throughout with a sealed carrier for the merge, every notes attempt re-reads the tip, `failure(e)` is the only exception-to-detail mapping in the new classes, the notes entry points validate the reference name, `history` drives the walk by hand and is correct for the root commit, empty trees, a file replaced by a directory, `maxCount <= 0` and `Integer.MAX_VALUE`, and the merger's conflict rule is JGit's own. The defects are at the edges again. Re-read by the orchestrator at `14269fe`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `InCoreMerger.java:124` (`merger.merge`), catches 135-142 (`NoMergeBaseException`, `IOException`); JGit 7.8.0 `StrategySimpleTwoWayInCore.add` 148-165 builds `new DirCacheEntry(tw.getRawPath(), stage)` | a tree fetched from a foreign remote that holds an entry such as `.GIT` or `git~1` | `dircache.InvalidPathException` (an `IllegalArgumentException`) escapes `merge` instead of ending as a `NotMerged` result (GDR-GEN-3); the same class as PLAN-04's L3-1, now through the merge | minor |
| L3-2 | `JGitRepositoryOperations.java:473-482` (`log`: `for (RevCommit commit : walk)` checks `maxCount` inside the loop; the catch is `IOException \| IllegalArgumentException`) | a repository whose commit after the `maxCount`-th has a missing parent or tree | the walk reads one commit ahead and the unchecked `RevWalkException` (a `RuntimeException`) escapes `log`; the same defect PLAN-05's review fixed in `RepositoryReader.history` | minor |
| L3-3 | `GitNotes.java:205-208` (`isNotesRef` checks syntax only) with `ReferenceUpdater.java:213-224` and JGit `RefUpdate.java:714-718` | `GitNotes.write` with `refs/notes/a/b` while `refs/notes/a` exists | JGit answers `LOCK_FAILURE` for the name conflict, the loop retries every attempt and returns `CONFLICT` "on every attempt" instead of `REJECTED` (PLAN-04's P4 reaching the new public API) | minor |
| L3-4 | `ReferenceUpdater.java:376-384` (the Javadoc of `unresolvedUnderLock`) against JGit 7.8.0 `RefDirectoryUpdate.tryLock` 46-60 | a lock held on a symbolic reference on the file backend | `tryLock` returns false before `setOldObjectId`, so a held lock also leaves the old id unset and enters the guarded batch; the Javadoc says a `LOCK_FAILURE` with an old id is the real one and one without is the DFS quirk. The leaf reads the batch as refused and reported as the same `LOCK_FAILURE`, so the effect is benign; the stated discriminator is wrong | minor |
| L3-5 (lead) | `ReferenceUpdater.java:405-411, 461` | JGit changes the text `io error` that `ReftableBatchRefUpdate.execute` sets | the lost-race versus storage-failure classification keys on the literal message; an unknown message is a retried `CONFLICT`. Correct at 7.8.0 only | minor, not re-read |
| L3-6 (lead) | `ReferenceUpdater.java:347-356` (a refused batch falls through to `update.update()` for a dangling alias) | a batch that ends `REFUSED` for a dangling alias on the in-memory backend | the update replaces the alias with no id comparison (`DfsRefUpdate`); the leaf finds it unreachable at 7.8.0 (the only non-`LOCK_FAILURE` rejection is the object-existence check), a latent hole | minor, not re-read |
| L3-7 (not shown) | `RepositoryReader.java:277` (`history`) | a rarely changed path in a repository with hundreds of thousands of commits | the walk visits the whole ancestry, one `TreeWalk` per parent per commit, retaining every parsed commit body; no cap | not shown by reading |
| L3-8 (lead) | `GitNotes.java:197` inside the `TreeSource`, `InCoreWriter.writeTree` catch 181 (`IOException \| GitAPIException \| JGitInternalException`) | a notes tree over JGit's streaming threshold (50 MiB) | an unchecked `LargeObjectException` from `NoteMap.load` escapes `write` while `read` maps it | minor, not shown by reading |

**PLAN-04's nine defects and the tag peel (the spec of PLAN-20), at `14269fe`:** all present. P1 `InCoreWriter` (lambda 119-123, catch 181; newly reachable through the merger, L3-1) — present; P2 (`InCoreWriter` 177, 241-248; `ReferenceUpdater` 133) — present; P3 the loops `for (int attempt = 1; attempt <= attempts; attempt++)` at `ReferenceUpdater.java:199` and `283` — present, re-read, and `GitNotes.write` inherits it (only `attempts < 1` is rejected); P4 — present, re-read (213-224); P5 — present for `compareAndSwap`, `compareAndDelete` and `compareAndSwapTree` (a search of `ReferenceUpdater.java` finds no `isValidRefName`, re-read), fixed for the notes entry points (`GitNotes.java` 110, 181, 205-208); P6 (`InCoreWriter.checkPaths` 189-202), P7 (193), P8 (`JGitRepositoryOperations` 252, 613) and P9 (`readAllBytes` at 698) — present, unchanged (leaf's reading, P6 to P9 not re-read at the new lines); P10 `ReferenceUpdater.parseCommit` 335-339 — present, re-read: `walk.parseCommit(id)`, the one new-code site that does not apply the review rule `parseAny`.

**Info (leaf's reading, not re-read):** `history` lists a clean merge that equals one parent and differs from the other (documented), rejects a one-character blank path although the merger accepts whitespace-only names, and skips symlinks and gitlinks (documented); the conflict carrier holds every path in one unbounded list and a criss-cross history (more than one merge base) is `CONFLICT` with no paths, told apart by variant only; `GitNotes` offers no removal (`NoteMap.remove` exists) and an identical note still creates a new notes commit; a note is read whole into a `byte[]` and details never carry note content (test `detailsCarryNoContent`).

**Layer 3 not shown by reading:** the cost of `history` (L3-7), the `LargeObjectException` reach (L3-8), the batch behaviour on a file-based reftable repository, and the concurrency behaviour of the guarded batch (exercised by tests, not shown by reading).

**Layer 3 verdict:** findings — 4 minor re-read (L3-1 to L3-4), 2 minor leads (L3-5, L3-6), 2 not shown by reading (L3-7, L3-8); P1 to P10 all present

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| `general.adoc` GDR-GEN-3 | Expected outcomes are results, not exceptions | deviates | L3-1 and L3-2 (unchecked exceptions escape), and the unchanged P1 to P3 sites |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | `@Nullable` on the new locals and parameters, package already `@NullMarked`; no static analysis shown |
| `general.adoc` GDR-GEN-4 / cui-logging | `CuiLogger`, `LogRecord` holder, catalogue for every INFO/WARN/ERROR | conforms | no new INFO/WARN/ERROR identifier; `LogMessages.adoc` unchanged; DEBUG lines at `ReferenceUpdater` 213 and 295, `REMOTE_REFUSED` reused |
| AGENTS.md | Javadoc on every public type and method | conforms | the new types, records and accessors are documented; one inaccuracy (L3-4) |
| AGENTS.md | No dependency without approval | conforms | one test-scope dependency added; the approval is recorded in the lane's text and the lane's lesson, not independently shown (L2-1) |
| AGENTS.md | Never touch `.github/project.yml` | conforms | no `.github` path in the diff |
| `general.adoc` GDR-NFR-3 | No secret in results or logs | conforms | details name a field and a reason, the conflict detail is a count, `failure(e)` names the class; the exceptions that escape carry a path in their message and are not the library's own detail |
| AGENTS.md | Tests: EasyMock for test doubles | deviates (pre-existing) | hand-written `Proxy`, lambda and fake-`Transport` doubles, no EasyMock in `git-core` (L2-3) |
| CLAUDE.md | Verify JGit claims and name the version | conforms | 7.8.0 named; the cited claims re-read hold, with one imprecision (L3-4) |
| the four PLAN-04 review rules folded into the spec | (1) `failure(e)`, (2) `parseAny`, (3) `updateRef(name, true)` with a symbolic test, (4) the concurrency assertion split | deviates (one of four) | (1), (3) and (4) applied (`GitNotesTest.symbolicNotesReference`, `safetyOnLocal`, `livenessInMemory`); (2) not applied in `ReferenceUpdater.parseCommit` (P10) |

## Metrics and Anomalies

- Tokens: 16,590,381 (the lane's figure, not re-verified). Duration: 23,506 s (about 6.5 h).
- Anomalies: `effort` is `medium` but self-reported (`model_source: self_reported`; the landing-time refresh could not measure the model); the three review numbers and `sonar_open_new_issues` are `unknown`, so `inbox landing-check` reports `complete: false` (missing `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`, `sonar_open_new_issues`); `archive-plan:unknown` (it runs after the landing is written). Phase 6 needed three firings of ci-verify, automatic-review and sonar-roundtrip and three fix tasks (12-14); the wait-region unified-triage loop-back budget shows spent 2 against granted 0. The finalize commit guard refused the round-2 inline edits twice (`no_sanction`, lesson `2026-11-18-13-002`); a fix task was created with an empty skills list (lesson `2026-11-19-19-002`); `manage-findings list --limit` was rejected three times (`2026-11-18-13-003`); the change-ledger holds no build row for 45 Maven builds (`2026-11-19-06-005`); all 18 dispatch-boundary rows are keyless (`2026-11-18-07-006`); `manage-change-ledger append --changed-paths-file` failed again, the sixth plan (`2026-11-18-13-004`); `git-workflow commit` reported `FETCH_FAILURE` for an empty index (`2026-11-19-19-004`).

## Routing and Merge Behavior

- Review: the lane reports "0 comment(s) found - 1 reviewed, 1 refused-structural" as prose only, and review rounds on PR #37 at heads `4733bb1` (round 1) and `cf833c4` (round 2). **Provider re-read:** `review-gate` for #37 is **undecidable** (`undecidable_read`: the parser cannot read CodeRabbit's "Minor comments: 1" review-body section), so `unresolved_review_threads` and `review_body_findings_total` are not re-verified; the landing's three review numbers are `unknown`, so there is no difference to record as a false claim.
- CI/merge: merged through the merge queue as a squash, `14269fe`; the CI abstraction reads state `merged`, merge commit `14269fef...`; `step.branch-cleanup.merge_mechanism=merge_queue`.
- Sonar: the landing records `sonar-roundtrip.new_code_issue_count=0` (confirmed) and says it counts issues without security hotspots, so it did not claim `sonar_open_new_issues`. **Re-read:** the quality gate of #37 is `OK` (new coverage 96.9 %, duplicated lines 0.0, hotspots 100 % reviewed); the gate is a verdict, not an open-issue count, so the count is not re-verified.
- Overlap: PLAN-05 ran alone (PLAN-04 had shipped, `finalize-step-sync-baseline` reports 0 upstream commits), so no rebase conflict in `AGENTS.md`, `doc/README.adoc` or `general.adoc` occurred. It overlaps the staged PLAN-20 on `git-core` main and test, `git-core.adoc` and `AGENTS.md`, and has changed the code PLAN-20 names: P1 to P10 are all still present and the merge added the same defect class to new code (L3-1 to L3-3); PLAN-20 was updated in the same act (see the reconciliation).

## Reconciliation Actions

- [x] row `status` -> `shipped` — `orchestrator queue --transition PLAN-05 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-05 --field pr --value 37`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-05 --field landing --value landings/PLAN-05.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-05 --field plan_marshall_plan_id --value plan-05-git-core-merge-read-notes`
- [x] epic.md narrative reconciled (PLAN-05 shipped; the lesson dispositions; the layer-3 report and PLAN-20's update; the incomplete landings; the dependency decision)
- [x] layer-1 verdicts persisted onto the spec (claims 0-5 corroborated)
- [x] PLAN-20 updated in the same act: `InCoreMerger.merge` joins item 3 (a), `GitNotes` the name-conflict item 3 (d) and the `LargeObjectException` lead 3 (b), a new item 3 (h) for the guarded batch, `JGitRepositoryOperations.log` and the EasyMock question join deliverable 4, four claims added (15-18), the Done-when base is now `14269fe`, and the overlap with PLAN-05 is retired
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-ups

- The layer-3 defects L3-1 to L3-8 above are folded into PLAN-20 (a collection plan the operator asked for); no new plan is staged.
- PLAN-06 (`build-guards`, the last foundation plan) is the next plan and follows PLAN-05. PLAN-20 changes the behaviour of public `git-core` and `git-worktrees` operations, so the operator may want it released before the 0.1.0 release that follows PLAN-06: a Maven Central release cannot be withdrawn.
