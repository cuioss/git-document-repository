# Landing Analysis: PLAN-20 — Collected leftover fixes (the worktrees package, git-core, git-testing, build guards)

epic: libraries-initial-implementation
workstream: WS-01
pr: #69 (https://github.com/cuioss/git-document-repository/pull/69), merged as `f7eda4e` (first parent `b30ec14`), through the merge queue after a rebase the operator authorised; PRs #67 and #68 were closed unmerged and replaced

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the lane's claims against git, the PR
> and the merged code. The landing message is `inbox/plan-20-leftover-fixes-2-021.md`; twenty candidate-lesson messages
> (001 to 020) came from the same plan.

## Deliverable Fidelity vs Spec

The spec numbers six deliverables and the landing facts agree (`deliverables_total=6, deliverables_done=6`). Evidence is the merge
diff `git diff b30ec14 f7eda4e` (49 files, +3663 / -411) and the leaf's reading of the merged code.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Worktrees: `IllegalArgumentException` mapped in `commit`, `worktreeStatus`, `worktreeRemove`; a rollback that runs every step; a failed reservation release that keeps the finished tree; reflog and alias resolution | shipped-as-specified | `JGitWorktreeOperations` (catch lists extended, `rollBack` through `attempt` 463-478, `releaseCompletedReservation` 389, `writeLinkedReflogs`); tests `invalidAutoCrlfFails`, `uncheckedRollbackStepContinues`, `failedReservationReleaseKeepsWorktree`, `uncheckedReflogFailureKeepsCommit`. The rollback wiring itself is not observed (L2-2) |
| 2. Branch-name rule and documentation of the bare-root refusal | shipped-modified | `isBranchName` refuses `HEAD`, `@`, a leading `-`, a `.lock` component and any `refs/` prefix; the last two go beyond what git refuses and the specification records it as a library rule (L3-4). `GitWorktreeListCompatibilityTest` checks against git |
| 3. `git-core` write path: `InvalidPathException` mapped, the retry loops, name-conflict decision, `refName` validation | shipped-as-specified | `InCoreWriter`, `ReferenceUpdater` (loops `attempt < attempts` from 0 at 227 and 364, names refused before any read), `InCoreMerger`; the `MAX_VALUE` test cannot fail at the base (L1-1, L2-3). The path check went further than the spec's list (L3-1, the major) |
| 4. Remote and read hardening: stored origin without query data, bounded `readFile`, `log` under the limit, the deterministic `publishLosesRace` | shipped-as-specified, with one gap | `JGitRepositoryOperations` (`storedOrigin` 373, public `READ_FILE_LIMIT_BYTES` 212, `readBlob` asks the size first); the new public constant is missing from the `AGENTS.md` Current state paragraph (L2-5); that the owner was asked for the bound of 4(b) is not shown (L1-3) |
| 5. `git-testing` leftovers: `DistinctTextGenerator`, `HermeticGitConfiguration` and its extension, the tests migrated off hand-made prefixes | shipped-modified | the hermetic fixture is used by one test only (`RedirectEndToEndTest`); no list of host-configuration dependants is recorded (L2-4); the six candidate tests of the spec's enumeration migrated |
| 6. Build guards: the fourth GDR-GEN-1 check `direct-dependency-isolation`, the negative runs recorded, the native-image test's shape | shipped-as-specified | `git-core/pom.xml` (new execution with `searchTransitive=false`, EasyMock test scope, a reworded description; the root `pom.xml` is untouched), `general.adoc`, `AGENTS.md`, `README.adoc` say "four checks"; `NativeImageMetadataTest` checks shape with a hand-written JSON reader (200 lines, oversized, no depth bound; the input is the module's own two files) |
| (added) `HermeticGitConfigurationTest`, `DistinctTextGeneratorTest`, `LocalRepositoryExtension` changes, `RecordingTransportProtocol`, `TestSigner` | added, explained | needed by deliverable 5 and the test migrations; every touched file is explained by a deliverable (leaf-reported) |

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`), by reading only: no build, no test
run, no write. The orchestrator re-read the layer-3 major and four of the layer-3 and layer-2 minors at their lines in `f7eda4e`
(git show) before recording them; points it did not re-read are marked "leaf-reported". The leaf read all main-code diffs and the
tests that carry the fail-first claims, but did not open the `InCoreMergerTest` and `RepositoryOperationsRecordsTest` diffs or
most of the `git-testing` test diffs.

- **Dispatch:** one read-only leaf per landing — reason: 49 files of merged `git-core` write-path, worktrees and build-guard code, read against a 368-line spec, is the depth the dispatch rule exists for, and its context stays apart from the drain of the 33 messages of two landings.
- **Verdict:** layer 1: 2 minor, 2 info, 2 not shown; layer 2: 4 minor, 2 info, 1 not shown; layer 3: **1 major**, 4 minor, 4 info, no blocker — see the sections.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | The Done-when demands a test that fails at the base for every behaviour fix of deliverables 1 to 4, while deliverable 3(c) says the `MAX_VALUE` test "terminates at the base too" (a double ends the loop); the integer overflow cannot be observed in bounded time, so the two clauses cannot both hold for 3(c) | checked | refutes | leaf-reported (`git-core.adoc` 601); `ReferenceUpdaterTest.java:1087` re-read: the test ends the loop by exception after 5 lost races |
| L1-2 | HYPOTHESIS "`Repository.isValidRefName` of JGit 7.8.0 accepts `HEAD`, a leading `-`, `a.lock/b` and `@`" (claim 5) | checked | confirms | the compatibility tests assert it and `git-core.adoc` 808-820 records it; persisted `corroborated` |
| L1-3 | HYPOTHESIS "a token in the query string and an uppercase-scheme URL stay in the stored origin" (claim 13), and that the bound of 4(b) is put to the owner through the question tool | checked / not shown | confirms / none | the first half is confirmed (`URIish` keeps the query in the path) and persisted `corroborated`; whether the owner was asked is not shown: the specification gives only a reason ("the caller's memory") and no owner-decision marker, unlike 3(g) |
| L1-4 | HYPOTHESIS "`SystemReader.checkPath` is host-dependent" (claim 11) | checked | confirms | `git-core.adoc` 262-266 records the owner decision (strict on every host); persisted `corroborated`; that the owner chose it is not visible from the repository |
| L1-5 | HYPOTHESIS "a consumer on an in-memory DFS repository reaches `DfsText`, which the native-image metadata does not list" (claim 22) | not_shown_by_reading | none | answered for the `git-core` main code (no `storage.dfs` import) and not for a consumer's DFS use; persisted `unverifiable` |
| L1-6 | Spec tension in 2(b): "the choice follows what git itself accepts" against listing the full `refs/heads/x` form, which git accepts | checked | refutes | resolved by the lane as "a rule of the library beyond git" (`git-core.adoc` 814-820); a documented, tested decision |
| L1-7 | The stale-state claims about `main` (base lines moved by PLAN-21 and PLAN-08, the worktrees defects present, no `git-store` caller passes `HEAD`) | checked | confirms | leaf-reported |

**Layer 1 verdict:** findings — 2 minor (L1-1, L1-3), 2 info (L1-4, L1-6), not shown by reading — 2 (L1-5, and the owner question in L1-3)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `JGitWorktreeOperations` | four named tests; the wiring of `rollBack` into `attempt` is unobserved | none | checked, with L2-2 |
| 2 | `isBranchName`, `GitWorktreeListCompatibilityTest` | `gitBranchNamesRefused` | none | checked |
| 3 | `InCoreWriter`, `ReferenceUpdater`, `InCoreMerger` | `InCoreWriterTest`, `ReferenceUpdaterTest`; the `HEAD` refusal and the overflow fix have no test that can fail (L2-1, L2-3) | none | checked, with L2-1 and L2-3 |
| 4 | `JGitRepositoryOperations` | `cloneStoresNoQueryData` (fails at the base through a failed clone, not through the stored token, and uses a `file:` remote, L2-6), `JGitCacheRepositoryTest` size limits, the deterministic `publishLosesRace` (the `StackOverflowError` of the test double traced and fixed) | none | checked, with L2-5 and L2-6 |
| 5 | `DistinctTextGenerator`, `HermeticGitConfiguration` | `DistinctTextGeneratorTest`, `HermeticGitConfigurationTest` (that the host configuration is really suppressed shows only when run) | none | checked, with L2-4 |
| 6 | `git-core/pom.xml`, `NativeImageMetadataTest` | the guard is proven by prose negative runs ("run once on 2026-11-23 and not committed", not repeatable by a check; the documented repeat command is `./mvnw validate -pl ...`, not one of the canonical commands) | none | checked, the run not shown |

| Finding | Claim | Verdict | Polarity |
|---------|-------|---------|----------|
| L2-1 | Decision D3(d) (`HEAD` is refused by `isValidRefName`) is documented (`ReferenceUpdater.java:71`) but no test passes `HEAD`, or any one-component name, to `compareAndSwap` or `compareAndDelete` (`grep HEAD` over `ReferenceUpdaterTest` is empty, re-read); only the `..` case is pinned. The declined CodeRabbit comment ("allow `HEAD`") agrees with the code, and a later change that allowed `HEAD` would pass the suite | checked | refutes |
| L2-2 | `uncheckedRollbackStepContinues` tests only the package-private `attempt()` with lambdas; a `rollBack` that ran `releaseTarget` outside `attempt()` would pass (`JGitWorktreeOperations.java:463-478`) — the unobserved-wiring gap that deliverable 5 itself names for PLAN-18 | checked | refutes |
| L2-3 | `maximalAttempts` (`ReferenceUpdaterTest.java:1087`, re-read) passes at the base too; the overflow fix at `ReferenceUpdater.java:227` and `:364` has no test that can fail (spec-mandated, but the Done-when fail-first clause does not cover it, L1-1) | checked | refutes |
| L2-4 | The hermetic fixture is used by one test; other tests still read the host configuration (hooks path pinned by hand, `core.autocrlf` per test); the spec asked to decide after reading which tests depend on the host, and no list is recorded in `git-testing.adoc` | checked | refutes |
| L2-5 | The `AGENTS.md` Current state paragraph omits the new public `READ_FILE_LIMIT_BYTES` and the `readFile` bound (`grep -c READ_FILE_LIMIT_BYTES AGENTS.md` at `f7eda4e` is 0, the constant is at `JGitRepositoryOperations.java:212`, re-read); the standing constraint says the paragraph is updated when a public type or method changes | checked | refutes |
| L2-6 | `cloneStoresNoQueryData` proves "no query data in the stored config" for a `file:` remote only, not for an https transport | checked | confirms (the property), refutes (the transport coverage) |
| L2-7 | The lane's "Sonar 0, 14 CodeRabbit comments and 5 Sonar issues fixed" is not shown by the diff; the fail-first runs for deliverable 4 were not run against the base (the lane says so in the PR body) | not_shown_by_reading | none |

**Layer 2 verdict:** findings — 4 minor (L2-1, L2-2, L2-4, L2-5), 2 info (L2-3, L2-6), not shown by reading — 1 (L2-7); every deliverable traces to code and to a test, the overflow fix of 3(c) to none that can fail

### Layer 3 — Correct

Re-read by the orchestrator at `f7eda4e` unless marked leaf-reported.

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `InCoreWriter.java:245-255` (re-read: `checkPaths` runs the strict `ObjectChecker` with `setSafeForWindows` and `setSafeForMacOS` over every edit, `TreeEdit.Delete` included) | a parent tree that already holds `aux.txt`, `trailing.` or `a:b` (stored by a push from Linux) and the edit `Delete("aux.txt")` | `NOT_FOUND` "edits: invalid path" and nothing written: the library can no longer remove such a file through `InCoreWriter` or `compareAndSwap`, which it could before the change (`SystemReader.checkPath` accepted it on Linux). The owner decision covers creating names, not deleting them; a `Delete` creates no entry and needs no portability check | **major** |
| L3-2 | `InCoreWriter.java:255` (leaf-reported) | `Put("README")` onto a tree that already holds `readme`; `Delete("a")` with `Put("a/b")` | the duplicate, case-fold and overlap check compares the edits with each other, not with the parent tree: the first writes two entries that collide on HFS+ and NTFS, which the new Javadoc says the strictness avoids; the second is refused as overlapping although converting a file into a directory this way is a legitimate edit | minor |
| L3-3 | `JGitWorktreeOperations.java:1012` (re-read: `catch (FileSystemException e)` moves to the parent for every subtype) | a registered worktree path behind a symlink loop or an unreadable directory | `AccessDeniedException` and `FileSystemLoopException` are resolved through the ancestor instead of failing: a path that is not that tree can compare equal to the parent-resolved one in `worktreeRemove` (`:733`) or the target guard; the Javadoc says it matches `Files.exists`, which is not a security comparison | minor |
| L3-4 | `JGitWorktreeOperations.java:610` (re-read: `isBranchName` refuses `HEAD`, `@`, a leading `-` and any `refs/` prefix) | `worktreeAdd` of `refs/foo`; `releaseBranchReservation` for a reservation file left earlier for `HEAD` or `refs/x` | refused where git accepts the name; a reservation left earlier can no longer be released through the API (refused before the lookup); documented and tested against git, but beyond the stated "follows git" rule | minor |
| L3-5 | `JGitRepositoryOperations.java:373` (re-read: `storedOrigin` returns empty for `isUnparsedAuthority`, `:1230`) | `cloneIfMissing` with a local policy and `//server/share/repo.git` (a valid POSIX or UNC form) | `REFUSED_BY_POLICY` "not a URL" where it was accepted; `cloneIfMissing`'s Javadoc and the specification name only the uppercase-scheme case; the credentials side stays safe and the `fetchCommit` path stores nothing | minor |
| L3-6 | `ReferenceUpdater.java:227` (leaf-reported) | `attempts = Integer.MAX_VALUE` | the loop cannot overflow but still runs about 2^31 iterations of file I/O against a held lock: it terminates, it is not bounded in practice; documented as a bound like any other | info |
| L3-7 | `ReferenceUpdater.java:272`, `:372` (leaf-reported) | a name conflict, an annotated tag | decided on a null `exactRef` before the operation (swap) or after the `LOCK_FAILURE` re-read (delete); `parseAny` does not peel; the caller's `TreeOperation` and `Signer` exceptions propagate by documented contract | info |
| L3-8 | `JGitRepositoryOperations.java:792` and `:539-548` (leaf-reported) | a blob over 16 MiB, a missing commit past the `log` limit | `readBlob` asks `getObjectSize` before opening and closes the reader; `log` drives `walk.next()` under the limit so a `RevWalkException` cannot escape; the size test allocates three 16 MiB blobs (memory-heavy, not wrong) | info |
| L3-9 | `JGitWorktreeOperations.java:463`, `InCoreMerger.java:146` (leaf-reported) | an unchecked rollback, release or reflog failure; an `InvalidPathException` from the merger | every step runs, the first failure is thrown with the later ones suppressed, no throwable is attached to a log record (`GIT_CORE-103`, `104`, `105` carry no exception, path or name); the merger returns `NotMerged(FAILED, class name)`; `releaseReservation` nulls its field before the release, so a failed release is not retried by a later rollback (left for `releaseBranchReservation`, as documented) | info |

**Layer 3 not shown by reading:** whether `direct-dependency-isolation` fires on a deliberately wrong optional dependency (a run);
that the hermetic reader suppresses the host configuration (`Delegate.getUserConfig` forwarding); the deadline and shutdown
handling of the concurrent callers in `DistinctTextGeneratorTest`; that the `.GIT` and `git~1` merger tests the spec asks for
exist (inferred only from the same mapping in `InCoreWriterTest`).

**Layer 3 verdict:** findings — 1 major (L3-1), 4 minor (L3-2 to L3-5), 4 info; not shown by reading — 4

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, no new package | conforms | `git-testing` adds its types to the exported package; `module-info.java` changes Javadoc only (leaf-reported) |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | new nullable values carry `@Nullable` (leaf-reported) |
| AGENTS.md | expected outcomes are results, not exceptions (GDR-GEN-3) | conforms | the formerly escaping exceptions are now `FAILED`, `REFUSED_BY_POLICY` or `REJECTED` results; the caller-code exceptions propagate by documented contract; the one deviation of note is the refused `Delete` of L3-1 |
| AGENTS.md | `CuiLogger`, `LogRecord` holder, catalogue (GDR-GEN-4) | conforms | `WARN.RESERVATION_NOT_RELEASED` (105) and its row in `LogMessages.adoc` (leaf-reported) |
| AGENTS.md | Javadoc on every public type and method | conforms | the new public types and members carry Javadoc (leaf-reported) |
| AGENTS.md | no dependency without approval | conforms | `git-core/pom.xml` adds EasyMock at test scope without a version (owner-approved by the spec) and the enforcer execution; no other artifact, the root `pom.xml` and `.github/` untouched (`git diff --stat` re-read) |
| AGENTS.md | Current state paragraph updated when a public type or method changes | deviates | L2-5 |
| spec constraint | GDR-GEN-1 list and the guard count match the POM executions | conforms | "four checks" in `general.adoc`, `AGENTS.md`, `README.adoc`; the execution excludes every direct compile and runtime artifact but jspecify, JGit and `cui-java-tools` (leaf-reported); whether it fires is not shown |
| spec constraint | cross-document sync of a changed outcome (Javadoc, specification table, `general.adoc`, `pm-mcp-core.adoc`) | conforms | the `REFUSED_BY_POLICY`, `REJECTED`, `FAILED`, `PATH_REFUSED` rows and the `pm-mcp-core.adoc` bullets are updated (leaf-reported); the exception is the `AGENTS.md` paragraph (L2-5) |
| spec constraint | secrets (GDR-NFR-3): no secret in a detail, a log record or a stored configuration | conforms | the stored origin is built from scheme, host, port and path only; details are class names or fixed text (leaf-reported) |
| spec constraint | a deliberate `catch (RuntimeException ...)` carries its justification; no throwable on a sanitised log record | conforms | a comment and `cui-rewrite:disable` at `JGitWorktreeOperations` 216, 395, 506, 831 and `LocalRepositoryExtension.close`; no log call passes a throwable (leaf-reported) |
| spec constraint | fail-first at the base for deliverables 1 to 4; negative runs prove the guards | not_shown_by_reading | by reading, 3(c) cannot fail at the base and `cloneStoresNoQueryData` fails there for another reason; the runs themselves are not shown |
| spec constraint | tests: one throwing call per `assertThrows`; concurrent scenarios end with a deadline, cancellation, `shutdownNow` | not_shown_by_reading | the new `assertThrows` lambdas hold one call each; `DistinctTextGeneratorTest`'s concurrent callers were not read |

## Metrics and Anomalies

- Tokens: the landing says `total_tokens=25976485`; no retrospective figure for this plan reached the inbox (the 20 messages carry findings and process observations, not the token totals), so there is nothing to reconcile it against. Wall: `total_wall_seconds=53696` (895 min), of which the lane names about 1.5 hours of CodeRabbit window waits.
- Anomalies: three PR numbers (#67, #68, #69) for one change; a rebase at `branch-cleanup` onto `b30ec14` voided four head-bound verdicts (simplify, ci-verify, automatic-review, sonar-roundtrip) and forced a second finalize pass; `pre_merge_gate record-merge` refused the truthful combination "rebased + merge_queue" (`action_on_queue_route`), so the step was recorded with `manage-status mark-step-done` and the same fact keys instead (lesson `2026-11-24-07-010`); planning phases advanced `status.json` before the orchestrator's Q-Gate pass (lesson `2026-11-24-07-009`); the lane's own build-ledger rows did not survive the worktree (lesson `2026-11-19-06-005`, recurrence added).

## Routing and Merge Behavior

- Review: 14 CodeRabbit comments, 5 Sonar issues (one BUG failing the reliability condition) and one further CodeRabbit major were triaged by the lane's account: 4 fix tasks and 4 inline edits applied, one item accepted as a documented trade-off, one declined with evidence (the reference-name guard refusing `HEAD` is the decided contract; L2-1 notes that no test pins it). The `review-gate` re-read gives 0 unresolved threads and 13 review-body findings with `handled: 0` (`body_findings_undisposed`; the handled count is not re-verified, the progress file is gone with the plan directory).
- CI/merge: merged as `f7eda4e` through the merge queue (PR state `merged`, merge commit read through `gh pr view`) after a content-clean rebase onto `b30ec14` that the operator authorised (the conflict gate's `overlap_no_content_conflict` against `no_overlap_only` overrode `use_merge_queue=true`); `cleanup_owed=false`.
- Sonar: the landing says `sonar_open_new_issues=0`; the merged-head read was repeated and is undecidable (`head_revision_unavailable`, analysis revision `5baca51`), so the `0` is not re-verified.
- Completeness: `inbox landing-check` reports `complete: false`, missing `effort`, `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled`.
- Collision: PLAN-20 ran beside PLAN-11 (merged first) and PLAN-13 (still running). Its diff touches `AGENTS.md` and `doc/specification/general.adoc` (also declared by PLAN-13) but not the root `pom.xml` (the spec declared it; only `git-core/pom.xml` changed), so the realised overlap with PLAN-13 is two files, not three; the rebase onto PLAN-11 was content-clean.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped `69`
- [x] row `landing` stamped `landings/PLAN-20.md`
- [x] row `plan_marshall_plan_id` stamped `plan-20-leftover-fixes-2`
- [x] claim verdicts persisted: claims 5, 11 and 13 `corroborated`, claim 22 `unverifiable` at `f7eda4e`
- [ ] epic.md: PLAN-03/04/05/06/18/19 defects routed to PLAN-20 retired or narrowed, Open Defect for the PLAN-20 layer-3 findings (NOT YET ROUTED), incomplete-landing entry, release Watch updated
- [ ] lessons: 21 messages dispositioned (see the log)
- [ ] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- L3-1 to L3-5, L2-1 to L2-6: reported to the operator, not routed. The major (L3-1) is a regression of the library's own write path on a repository that already holds a Windows-invalid name; no `git-store` caller is known to hit it.
- The open defects of PLAN-03, PLAN-04, PLAN-05, PLAN-06, PLAN-18 and PLAN-19 routed to PLAN-20 are closed by this landing as far as the leaf traced them (deliverables 1 to 6 trace to code); residues are L3-2 to L3-5 above.
