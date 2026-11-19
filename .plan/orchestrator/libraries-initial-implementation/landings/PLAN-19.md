# Landing Analysis: PLAN-19 — git-worktrees: hardening of the taken-over operations

epic: libraries-initial-implementation
workstream: WS-01
pr: #31 (https://github.com/cuioss/git-document-repository/pull/31), merged through the merge queue as `e2aced9` (squash, parent `de5c923`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-19-git-worktrees-hardening-018`
> after verifying it against git, the CI abstraction, the Sonar read and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git show --stat e2aced9`, 15 paths: `git-worktrees` (`JGitWorktreeOperations`, `LinkedWorktrees`, `WorktreeOperations`, `GitWorktreesLogMessages`,
`doc/LogMessages.adoc`, five test classes) and the documents `doc/specification/git-worktrees.adoc`, `doc/specification/general.adoc`, `doc/README.adoc`,
`doc/clients/pm-mcp-core.adoc`, `AGENTS.md`. `git-core`, `git-testing`, every `pom.xml` and `.github` are untouched, so the spec's "no change to `git-core`" holds and
`GitOutcome.java` — the file PLAN-04 was told it shares with this plan — was **not** touched; the overlap that was accepted at the prompt did not materialise at that file.
Shared with PLAN-04 in the diff: `doc/specification/general.adoc` only (one file, below the prompt threshold). Of the spec's Expected Surface every entry the diff touches is declared
except `AGENTS.md`, `doc/README.adoc`, `doc/clients/pm-mcp-core.adoc`, which the owner-approved new API and the changed outcomes force (L1-3).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Input guards of `worktreeAdd` (L3-1, L3-3, L3-6 of PLAN-03) | shipped-as-specified | branch name checked with `Repository.isValidRefName(refs/heads/<name>)` before anything is claimed (`JGitWorktreeOperations.java:177-183`, `549-554`), refused as `REFUSED_BY_POLICY` with the field name only, the outcome `git-core` uses for an invalid refspec; root guard `174-176` (`PATH_REFUSED`); `refuseTarget` `561-584`; tests `invalidBranchNameRefused`, `linkedRootRefused`, `targetInsideAdministrationRefused` |
| 2. Result contract (L3-2, L3-7, L3-5, L3-8) | shipped-modified (closed for the named cases, see L3-1 to L3-6 below) | `NoWorkTreeException` added to the catches of `worktreeList` (648) and `commit` (742), `worktreeRemove` inherits it through `worktreeList`; `catch (IOException \| RuntimeException)` around the add (204, 210); `deleteCreatedBranch` reads the `RefUpdate` result (396-414, `confirmNotLeft` 421); reflog failure keeps the commit (`writeLinkedReflogs` 754-761, `WARN.REFLOG_NOT_WRITTEN` carries the commit id only); outcome lists in the Javadoc, `git-worktrees.adoc` and the GDR-GEN-3 table |
| 3. Races and path identity (L3-4, L3-9) | shipped-modified (the stale-reservation way out is new public API, owner-approved during the run) | reservation taken for a new branch as for an existing one (257) and released after checkout (292) or after the created branch is deleted in the rollback (440-442); `releaseBranchReservation(Path, String)` (`WorktreeOperations.java`, GDR-WT-4, `GIT_WORKTREES-3`); `realPath` in `worktreeRemove` and `unstageLinkedRoots` |
| 4. Tests | shipped-as-specified, with two items not shown by reading | a test per fix in `JGitWorktreeOperationsTest` (+20 methods), accessor tautologies of `AbsenceContractsTest` and `WorktreeOperationsRecordsTest` replaced, `WorktreeStatusTest` (+65 lines) pins the nested-linked-root and clean-filter behaviours, `GitWorktreeListCompatibilityTest` extended by `locked` / `prunable` and `git worktree remove` (gated by `assumeTrue(gitInstalled())`) |

The lane reports 6 deliverables; the spec carries 4 — the lane's outline split them (the second outline dispatch revised the outline to 6, see lesson `2026-11-18-07-004`), so the number is not a mismatch.

Claims of the landing message against the diff: the guards, the bare-repository results, the add rollback, the reserved new branch, the real-path comparison, the new API, the `WORKTREE_NOT_FOUND` change for a foreign repository at a registered path
(the lane's owner decision, consistently updated in the Javadoc, the spec table, the `general.adoc` row and `pm-mcp-core.adoc`), "no change to `git-core`", and the three TASK-11 fixes (the `LOCK_FAILURE` re-read at 421-426, the reflog catch at 758, the reservation held past checkout at 292 and 440-442) are all borne out.
The test count is not: the PR body names 534 tests and the retrospective 522 -> 537 (+15), while `git-worktrees/src/test` gains 25 `@Test` methods and loses none (`+25/-0` by annotation count; the reactor total cannot be counted by reading) — recorded as **unverified**.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `e2aced9`, and the JGit sources in the local 7.8.0 sources jar. By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: reading merged code against the spec is the depth the dispatch rule exists for, and one landing's read must not share context with the orchestrator's reconciliation of the 17 lessons drained in the same scan.
- **Verdict:** layer 1 — 1 minor (L1-2); layer 2 — 4 minor, 3 not shown by reading; layer 3 — 1 major (L3-1), 7 minor, 1 documented-by-design; 8 of the 9 PLAN-03 defects are closed, 1 only partly (L3-9), and the documented outcome lists (L3-8) are partly still short.
- **Re-read by the orchestrator at `e2aced9`:** `JGitWorktreeOperations.java` 168-215 (add guards and the outer catches), 286-292, 360-470 (`releaseReservation`, `releaseTarget`, `deleteCreatedBranch`, `confirmNotLeft`, `rollBack`, `attempt`), 545-584 (branch and target guards), 655-707 (`worktreeRemove`), 720-761 (`commit`, `writeLinkedReflogs`), 925-945 (`canonical`, `realPath`); `LinkedWorktrees.java` 180-220; `WorktreeOperations.java` 50-60 and 222-230; `JGitWorktreeOperationsTest.java` 1365, 1708, 1725 and the `invalidLogAllRefUpdatesKeepsCommit` test (1735-1757); the `@Test` counts by `git diff e89cf86 e2aced9`; and in the JGit 7.8.0 sources jar `DefaultTypedConfigGetter.getEnum` (throws `IllegalArgumentException` for an unmatched value, 64-114), `WorkingTreeOptions` (47-55, `core.autocrlf` read by `getEnum`), `FileTreeIterator` (71), `AddCommand` (211, `new FileTreeIterator(repo)`), `LockFile.lock` (172, `mkdirs`). Two leaf line references were wrong and are corrected here: `FailingCheckoutFS` is at test line 1365 (the leaf said 286) and the `git-core` refspec check is at `JGitRepositoryOperations.java:287` (the leaf said 235). **Not re-read** (leaf-only, recorded as unverified leads): the exact rule set of `Repository.isValidRefName` for a component ending in `.lock`, a leading `-`, `HEAD` and `@`; `RefUpdate.delete` returning `LOCK_FAILURE` for a held lock; that JGit's checkout calls `FS.lastModifiedInstant` per written file; and the test files other than the lines named above.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | The spec's premise that a way out of a crashed reservation "may need new public API, an owner decision" and the Done-when do not contradict each other; `releaseBranchReservation(Path, String)` is that API, its Javadoc names every outcome | checked | confirms | `WorktreeOperations.java` (the new method), `doc/specification/git-worktrees.adoc` GDR-WT-4 |
| L1-2 | The spec is silent on `worktreeAdd` for a bare root: `refuseTarget` refuses every target inside the common directory (567) and for a bare root the common directory is the root, so the add can never succeed there (`PATH_REFUSED`, "is inside the git directory"); `git worktree add` itself works on a bare repository. The behaviour change is mentioned nowhere (not in the PR, the spec table or the Javadoc) | checked | refutes | `JGitWorktreeOperations.java:563-568` (re-read) |
| L1-3 | The spec's Expected Surface omitted `AGENTS.md`, `doc/README.adoc` and `doc/clients/pm-mcp-core.adoc`, which the new public API and the changed outcomes make change | checked | confirms | the diff stat |
| L1-4 | The Done-when items "fails at the base and passes at the head", "`verify` passes" and the Sonar read are run- and provider-side facts | not shown by reading | none | (the Sonar read is settled below) |

Persisted onto the spec with `corpus set-verdict` (producer `libraries-initial-implementation/analyze`, at `e89cf86`, the commit the claims describe): claims 0 to 5 corroborated (claim 1 with the `LockFile.lock` line 172 read now), claim 6 unverifiable (both clauses are JGit run behaviours, pinned by tests of the PR), claim 7 unverifiable (the verify-first clause is a process commitment the merged code does not show). L1-1 and L1-3 name no claim of the spec's `## Claim Labels`, so they persist nothing.

**Layer 1 verdict:** findings — 1 minor (L1-2); 1 not shown by reading (L1-4); 2 confirming

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | guards at 174-184, 549-584 | `invalidBranchNameRefused` (`../../../x`, a space, a trailing `.lock`; asserts no file at the escape path, no claimed target, unchanged tree), `linkedRootRefused`, `targetInsideAdministrationRefused` (`.git`, `.git/worktrees`, a linked tree) | none | checked |
| 2 | 204-214, 396-447, 648, 742, 754-761 | `bareRepositoryFails` (list, remove, commit; asserts `FAILED` with the class name only), `uncheckedFailureRolledBack` (injects an `IllegalStateException` inside JGit's checkout through `FailingCheckoutFS`; cannot pass vacuously, an unfired seam makes the add succeed), `branchDeletionResultRead`, `heldRefLockReported`, `reflogFailureKeepsCommit`, `invalidLogAllRefUpdatesKeepsCommit` | the behaviour change `IDENTITY_MISMATCH` -> `WORKTREE_NOT_FOUND` is stated by the lane and documented | checked |
| 3 | 257, 292, 440-442; `LinkedWorktrees.releaseBranch` 206-213; `realPath` 943 | `reservedBranchCheckedOut` (pre-creates the reservation file and asserts `BRANCH_CHECKED_OUT` — a deterministic stand-in for the window, no test races two real additions where one creates the branch), `removeThroughSymlink`, `linkedRootThroughSymlinkNotStaged` (fail at the base); the symlink tests skip via `assumeTrue(false)` on a file system without symlinks; `releaseBranchReservation` tests at 1415-1500 | none | checked |
| 4 | — | `WorktreeStatusTest` pins both hypotheses; the compatibility test is gated | `AGENTS.md`, `doc/README.adoc`, `doc/clients/pm-mcp-core.adoc`, `WorktreeStatusTest`, `LogMessages.adoc` are all explained by the new API and the changed outcomes | checked |

Layer-2 findings:

- **L2-1 (minor).** `reflogFailureKeepsCommit` asserts `assertTrue(Files.isDirectory(linkedLog), "the reflog was not written")` (test line 1725) on a directory the test created itself (1708): that one assertion cannot fail; the commit-id and log assertions around it carry the test. `AbsenceContractsTest.failedResultWithoutValue` asserts a null value of a non-`OK` result, which is structurally always true but sits next to assertions that can fail.
- **L2-2 (minor).** The stale-reservation race of PLAN-03 L3-4 is tested by its precondition, not by a race (see the deliverable 3 row).
- **L2-3 (minor).** The unrequested second behaviour change — `worktreeAdd` on a bare root now always `PATH_REFUSED` (L1-2) — is in no test and no document.
- **L2-4 (minor).** `FailingCheckoutFS` (test line 1365) is a hand-written subclass of `RefusingFS`, not an EasyMock double (AGENTS.md: "EasyMock for test doubles"; the file uses no EasyMock). A subclass is the only way to keep the real `RefusingFS` behaviour; the seam depends on JGit 7.8.0 calling `FS.lastModifiedInstant` per written file, so a JGit upgrade would turn it silent — the tests would then fail on the success outcome, so the dependency is visible.
- **L2-5 (not shown by reading).** Whether the extended compatibility test passes: `locked` / `prunable` in `git worktree list --porcelain` need a recent Git (2.31 or newer by the leaf's reading, not verified), and the test fails rather than skips on an older installed Git.
- **L2-6 (not shown by reading).** Whether `nestedLinkedRootStatusAgainstCommit` and `cleanFilterRefused` pass: both pin JGit run behaviours (spec claim 6).
- **L2-7 (not shown by reading).** "534 tests" / "522 -> 537": see the fidelity section.

**Layer 2 verdict:** findings — 4 minor (L2-1 to L2-4); 3 not shown by reading (L2-5 to L2-7)

### Layer 3 — Correct

One major and seven minor defects; no blocker. The code is careful in the large: the guards precede every claim, the reservation covers the whole critical section including the rollback, the rollback order deletes the created branch before the reservation is released, the `LOCK_FAILURE` handling re-reads the ref instead of inferring a move, and the details name a class or a field, never a caller string. The defects are at the edges again. Re-read by the orchestrator at `e2aced9`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `JGitWorktreeOperations.java:742` (`commit`), `854` (`worktreeStatus`), `704` (`worktreeRemove`, which calls `status().call()` at 696); the catches list `GitAPIException \| IOException \| JGitInternalException \| NoWorkTreeException` and no `IllegalArgumentException`, while `worktreeAdd` (162) and `worktreeList` (648) catch it | a repository whose `config` holds a value of a `core.*` enum that names no constant, for example `core.autocrlf = bogus`, then `commit`, `worktreeStatus` or `worktreeRemove`. JGit 7.8.0: `AddCommand` (211) builds `new FileTreeIterator(repo)`, which reads `WorkingTreeOptions` (`FileTreeIterator` 71) whose constructor reads `core.autocrlf` through `Config.getEnum` (47-48), and `DefaultTypedConfigGetter.getEnum` ends with `throw new IllegalArgumentException` for an unmatched value (110-113) | an unchecked `IllegalArgumentException` escapes instead of a `GitResult` (GDR-GEN-3). It is the same exception class and the same JGit getter TASK-11 closed for the reflog step only (`invalidLogAllRefUpdatesKeepsCommit` proves the getter throws), so the PR fixed one reach of the class and left three; no test covers it. Reachable only with a malformed repository config, hence not a blocker | **major** (the leaf's rating; the trigger is a malformed config) |
| L3-2 | `JGitWorktreeOperations.java:381-382` (`releaseTarget`: `Files.list(target)` then `entries.toList()`), `457-468` (`attempt` catches `IOException` only), `433-447` (`rollBack`) | an `UncheckedIOException` from the directory stream while the rollback releases the target (target removed or made unreadable during the rollback) | `rollBack` aborts before `deleteCreatedBranch` (441) and `releaseReservation` (442): the created branch is left and the reservation file stays, so every later addition of that branch ends `BRANCH_CHECKED_OUT` until `releaseBranchReservation` is called. The outer catch (210) hides the cleanup failure as a suppressed exception | minor |
| L3-3 | `JGitWorktreeOperations.java:292` (`releaseReservation()` on the success path), `363-369` (the field is nulled before `LinkedWorktrees.release`), `204-214` | the common directory becomes unwritable between checkout and release | an `IOException` at 292 propagates from `run()` after the working tree is complete; the catch calls `rollBack`, which deletes the finished target, the administration directory and the created branch, and returns `FAILED`; since the field is already `null`, the reservation file is never retried and stays as a stale one | minor |
| L3-4 | `JGitWorktreeOperations.java:943-945` (`realPath`: `Files.exists(path) ? path.toRealPath() : ...`), called for every registered tree in `worktreeRemove` (663, 666) and `unstageLinkedRoots` (773) | a registered worktree removed concurrently between `exists` and `toRealPath`, or unreadable | `NoSuchFileException` or another `IOException` ends `worktreeRemove` of an unrelated tree and every `commit` of the main working tree as `FAILED`; the base compared paths without I/O, so this is a new failure mode of the PR | minor |
| L3-5 | `JGitWorktreeOperations.java:944` (a missing path returns the normalised absolute path without resolving a symlinked ancestor; `canonical` at 933-940 does) | a prunable worktree registered as `/real/wt` (directory gone), removed through the alias `/link/wt` | matches neither `realPath(candidate)` nor `candidate.path().equals(target)` (666): `WORKTREE_NOT_FOUND` although the alias names it; PLAN-03 L3-9 is closed for existing paths only | minor |
| L3-6 | `JGitWorktreeOperations.java:758` (`catch (IOException \| IllegalArgumentException e)`) | any other unchecked exception from `appendLinkedReflogs` (an `UncheckedIOException`, an `InvalidPathException` from a ref name) | not caught here nor by `commit`'s catch (742): `commit` throws although the commit and its branch are already stored; PLAN-03 L3-5 is closed for the known failures only | minor |
| L3-7 | `WorktreeOperations.java:56-57` (worktreeAdd: the branch is reserved "until the new working tree is registered") and `226` (`@param path`: "must lie inside `root` and not exist or be empty") | reading the Javadoc | the code holds the reservation until after checkout and unlock (292) and the spec says so (`git-worktrees.adoc:76-79`); the `path` parameter omits the refusals for the git directory, the administration area and another linked tree. The release API's own Javadoc is complete | minor |
| L3-8 | `JGitWorktreeOperations.java:550` (`Repository.isValidRefName(refs/heads/<name>)`) | a name JGit 7.8.0 accepts and git rejects or reads differently: `HEAD`, a leading `-`, a component ending `.lock` inside the name (`a.lock/b`), `@`, or a caller passing the full `refs/heads/x` (leaf's reading of the 7.8.0 rule, **not re-read**) | the path escape and the control characters are closed — no file leaves `refs/` and no newline reaches the `HEAD` file — but a consumer that uses the `git` binary on the same repository may reject such branches; GDR-WT-1 does not say which rule applies | minor |

**Documented by design, not a defect:** `LinkedWorktrees.releaseBranch` (206-213) deletes the reservation file of the named branch with no check of owner or age, because the reservation is an empty file without pid or timestamp (184-190); `releaseBranchReservation` therefore can steal a live reservation if the caller breaks the documented precondition "no addition of that branch runs in any process" (`WorktreeOperations.java` Javadoc and GDR-WT-4). With a live addition A, a released reservation lets B adopt the branch, and A's rollback then deletes B's branch, because A's expected-id delete checks the tip, not the owner. There is no test of the live-reservation case; the reservation file name is the SHA-1 of the exact ref name, so on a case-insensitive file system `Foo` and `foo` share a ref file but not a reservation. `refuseTarget` also does not refuse a target that is an ancestor of an absent (prunable) registered root (570-579).

**PLAN-03 defects at `e2aced9`:** L3-1 closed (`refuseBranchName`), L3-2 closed (`NoWorkTreeException` in list, remove through list, commit), L3-3 closed (root guard), L3-4 closed (the reservation lifecycle, verified over every exit path of the add), L3-5 closed for the known failures (L3-6 above), L3-6 closed (`refuseTarget`), L3-7 closed for expected failures (residue L3-2, L3-3), L3-8 partly (the always-`PATH_REFUSED` bare add and the `IllegalArgumentException` outcome of L3-1 are not documented), L3-9 partly (L3-5 above).

**Layer 3 not shown by reading:** whether JGit's checkout reaches `FS.lastModifiedInstant` per file as the failure seam assumes; whether the hypotheses of spec claim 6 hold on the JGit run path (the tests pin them); Log and detail content was read and shows no GDR-NFR-3 violation (INFO `GIT_WORKTREES-3` carries no argument, WARN `GIT_WORKTREES-101` the commit id only, bare-repository details the class name only).

**Layer 3 verdict:** findings — 1 major (L3-1); 7 minor (L3-2 to L3-8); 1 documented-by-design; 2 not shown by reading

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| `general.adoc` GDR-GEN-3 | Expected outcomes are results, not exceptions | deviates | `IllegalArgumentException` from `Config.getEnum` escapes `commit`, `worktreeStatus` and `worktreeRemove` (L3-1); the rollback (L3-2) and the reflog (L3-6) catches are narrower than the failures they guard; the add, the list and the bare-repository cases conform |
| `general.adoc` GDR-GEN-4 | `CuiLogger`, `LogRecord` holder, catalogue for every INFO/WARN/ERROR | conforms | `GitWorktreesLogMessages` INFO 3, WARN 101, WARN 102; each is in `git-worktrees/doc/LogMessages.adoc` |
| AGENTS.md | Javadoc on every public type and method | deviates | the new method and the changed outcome lists are documented; two sentences are stale (L3-7) |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | new fields `@Nullable` (235-238); the new method takes non-null parameters |
| AGENTS.md | JPMS; no new package | conforms | no `module-info.java` or package change in the 15 paths |
| AGENTS.md | Tests: EasyMock for test doubles | deviates (reasoned) | `FailingCheckoutFS` is a hand-written `RefusingFS` subclass (L2-4) |
| AGENTS.md | No dependency without approval | conforms | no `pom.xml` in the diff |
| AGENTS.md | Never touch `.github/project.yml` | conforms | no `.github` path in the diff |
| `git-core.adoc` GDR-CORE-1 | The library never starts a process | conforms | every repository is opened with the injected FS; only the compatibility test starts `git` |
| `general.adoc` GDR-NFR-3 | No secret or caller string in results or logs | conforms | refusal details name the field (121, 553, 882), bare-repository failures the class name (969) |
| CLAUDE.md | Verify JGit claims and name the version | conforms | 7.8.0 named in Javadoc, spec and tests; the claims re-read here hold (`getEnum`, `LockFile`) |

## Metrics and Anomalies

- Tokens: 12,266,056 (the lane's figure, not re-verified).
- Duration: 12,168 s (about 3.4 h), of which about 31 minutes waiting for the CodeRabbit rate window (lesson `2026-11-18-13-005`).
- Anomalies: `effort` was never stamped (`unknown`, the stamp's `medium` entered at init without a measured source); the three review numbers are `unknown` because no finalize step records them, so `inbox landing-check` reports `complete: false` (missing keys `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`). The first outline dispatch froze criteria before four open operator questions were answered, costing a fourth question and a third outline dispatch (731,687 tokens, 24% of the plan; lesson `2026-11-18-07-004`). All 36 Maven builds logged the configured-disabled build-server line at WARNING (lesson `2026-11-18-18-004`). The execute envelope reports `4/10` and `1/11` tasks done with every task done (lesson `2026-11-19-06-004`). The change-ledger holds no build-duration row for 36 builds (lesson `2026-11-19-06-005`). `manage-change-ledger append --changed-paths-file` failed twice (lesson `2026-11-18-13-004`, fourth plan). The lane's accumulator for finalize usage was corrected once by hand (tokens and tool calls exact, duration off by 3 ms).

## Routing and Merge Behavior

- Review: CodeRabbit raised three findings, fixed by TASK-11 in the same PR (`9b4f3b8`): the `LOCK_FAILURE` inference, the reflog `IllegalArgumentException`, the reservation released before checkout; two thread replies were resolved on the provider and one review-body finding answered as a batched comment. **Provider re-read:** `review-gate` for #31 returns `unresolved_review_threads: 0`, `review_body_findings_total: 2` and `review_body_findings_handled: 0`, `merge_allowed: false`, `body_findings_undisposed` (two inline review-body findings by CodeRabbit). The landing's three review numbers are `unknown`, so there is no difference to record as a false claim; the gate's own `handled: 0` is a count of dispositions the provider does not carry, so `review_body_findings_handled` is **not re-verified** (the progress file was not read), and the two body findings are recorded as an open lead, not as a contradiction. The merge went through on the operator's instruction given in the run, the owner decision the landing states ("finalize was told to merge when green although AGENTS.md otherwise forbids merging without the user's say-so").
- CI/merge: merged through the merge queue as a squash, `e2aced9`; the CI abstraction reads state `merged`, merge commit `e2aced97...`. `build/sonar-build` failed once on a Maven Central resolution failure and passed on re-run (lesson `2026-11-19-06-003`). Three unrelated commits are on `main` around it (`de5c923` CI matrix, `24432f0` and `61ac7e1` workflow updates).
- Sonar: the landing claims `sonar_open_new_issues=0`. **Re-read:** the quality gate of #31 is `OK` (new coverage 96.2 %, duplicated lines 0.0, hotspots 100 % reviewed) and the hotspot read returns no hotspot; the open new-code issue count is the number the gate conditions do not give directly, and the gate-status OK is recorded, not a count (the `search` verb has no status column). Security hotspots: 0 fetched, which the landing said it did not read.
- Overlap with the parallel PLAN-04: one shared file (`doc/specification/general.adoc`); `GitOutcome.java` was not touched. PLAN-04 has not landed, so whether the two edits of `general.adoc` conflict is not yet observable.

## Reconciliation Actions

- [x] row `status` -> `shipped` — `orchestrator queue --transition PLAN-19 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-19 --field pr --value 31`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-19 --field landing --value landings/PLAN-19.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-19 --field plan_marshall_plan_id --value plan-19-git-worktrees-hardening`
- [x] epic.md narrative reconciled (PLAN-19 started and shipped; the PLAN-03 Open Defect partly retired and re-routed; PLAN-19 layer-3 report; incomplete landings; the lesson dispositions)
- [x] layer-1 verdicts persisted onto the spec (claims 0-5 corroborated, 6 and 7 unverifiable)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-ups

- The layer-3 defects L3-1 to L3-8 above are reported to the operator; they become a plan only on the operator's word.
