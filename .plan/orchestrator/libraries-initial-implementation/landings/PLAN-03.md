# Landing Analysis: PLAN-03 — git-worktrees: linked working trees, commit from a working tree, working-tree status

epic: libraries-initial-implementation
workstream: WS-01
pr: #30 (https://github.com/cuioss/git-document-repository/pull/30), merged through the merge queue as `e89cf86` (squash, parent `b5ee5d7`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-03-git-worktrees-import-020`
> after verifying it against git, the CI abstraction and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git diff b5ee5d7 e89cf86`, 20 paths: `git-worktrees/` (pom, `module-info`, four main classes, six test classes, `doc/LogMessages.adoc`), and the
documents `doc/specification/git-worktrees.adoc`, `doc/specification/general.adoc`, `doc/clients/pm-mcp-core.adoc`, `doc/concepts/module-structure.adoc`,
`doc/README.adoc`, `README.adoc`, `AGENTS.md`. `git-core`, `git-testing` and `.github/project.yml` are untouched. Of the spec's Expected Surface every entry is
touched; the one path outside it is `doc/specification/general.adoc` (dependency table and GDR-GEN-1 wording), which the spec's own Standing Constraints
(module state, dependency table) explain. PR #29 (`b5ee5d7`, the EasyMock replacement of the `git-testing` doubles) merged between the two plans and is not part of this diff.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Module wiring, `LinkedWorktrees` moved with its test | shipped-as-specified | `git-worktrees/pom.xml` (compile scope `git-core` only; `git-testing` test scope), `module-info.java` `requires transitive de.cuioss.git.document.core` and `exports`, `GitWorktreesLogMessages` with INFO 1, INFO 2 and WARN 100 all in `git-worktrees/doc/LogMessages.adoc`; `LinkedWorktrees` 258 lines (from 172) and `LinkedWorktreesTest` 329 lines (from 85) |
| 2. `WorktreeOperations`: add / list / remove, commit from a working tree, hooks refused | shipped-modified (the copy grew well beyond "plain copy": see L2-1) | `JGitWorktreeOperations.worktreeAdd` (133), `worktreeList` (512), `worktreeRemove` (543), `commit` (593-628, hooks `pre-commit` / `commit-msg` / `post-commit` checked before anything is staged, `RefusingFS` as the backstop); outcomes from `GitOutcome` |
| 3. Working-tree status, three sets, no digest | shipped-as-specified | `worktreeStatus` (702-720) and the `WorktreeStatus` record; one test scenario per JGit category in `WorktreeStatusTest`, a linked-worktree and a bare-repository scenario |
| 4. Tests, specification, client document, `AGENTS.md` | shipped-modified | six test classes; the worktree part of `GitOperationsContractTest` does not exist (the client document settles that the class tests only `GitCredential`, L1-4); `git worktree list` compatibility test runs only when a Git binary is present |

Claims of the landing message against the diff: the four deliverables, the atomic administration-directory claim, the post-commit hook refusal before the branch moves, the identity
check in `worktreeRemove`, the exclusion of linked roots from staging, the tolerant listing, the unknown-start-point rejection and the relative-`gitdir` resolution are all borne out.
"One deliberate behaviour change" (also the PR body) is not (L2-1). "500 tests", "the compatibility test ran against the installed Git" and "Sonar 0 new-code issues" are run- and provider-side facts the diff neither shows nor contradicts. The open item the lane states
(`worktreeList` on a bare repository throws `NoWorkTreeException` uncaught) is **confirmed** (L3-2) and is wider than stated.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `e89cf86`, and the JGit sources in the local 7.8.0 sources jar. The reading is by reading, no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing, on the `orchestrator.analyze` surface (resolved level `level-3`) — reason: reading merged code against the spec is the depth the dispatch rule exists for, and the landing's read must not share context with the orchestrator's reconciliation of the lessons drained in the same scan.
- **Verdict:** layer 1 findings — 1 minor; layer 2 findings — 3 minor, 2 not shown by reading; layer 3 findings — 1 major, 8 minor, 2 not shown by reading.
- **Re-read by the orchestrator at `e89cf86`:** `JGitWorktreeOperations.java` lines 133-260 (add, the `run` order of claim, reservation, branch creation, registration), 300-320 and 352-363 (branch creation by `updateRef`, `deleteCreatedBranch`), 504-560 (`checkedOut`, `worktreeList`, `worktreeRemove`), 596-657 (`commit`, `unstageLinkedRoots`), 659-682 (`appendLinkedReflogs`), 702-720 (status), 786-794 (`failure`); `LinkedWorktrees.java` 70-145 and 170-200; `git-worktrees/pom.xml` 37-42; the `LinkedWorktrees` class Javadoc ("four points", line 49); `AbsenceContractsTest.java` 36-60; JGit 7.8.0 `RefDirectory.fileFor` (1433-1436), and that neither `RefDirectory`, `RefUpdate`, `RefDirectoryUpdate` nor any file of `git-core/src/main` or `git-worktrees/src/main` validates the name (the one `Repository.isValidRefName` use is `git-core` `JGitRepositoryOperations.java:287`). **Not re-read** (leaf-only, recorded as unverified leads): JGit `LockFile.java:172` (`mkdirs` of the lock's parent), `RefDirectory.java:213-214` (`logsDir`), `FS.findHook`, `RefUpdate.setRefLogMessage(null, false)`, and the test files other than `AbsenceContractsTest`.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | OBSERVED "`LinkedWorktrees` 172 lines and `LinkedWorktreesTest` 85 lines exist in `pm-provider-git`", the worktree operations are declared on `GitOperations`, and the HYPOTHESIS "no three-set status exists, `worktreeSha` builds the digest from the working tree": all three true (the leaf could not read `pm-mcp-core`; the orchestrator did, read-only, at `287e1a86`) | checked | confirms | `LinkedWorktrees.java` 172 lines, `LinkedWorktreesTest.java` 85; `GitOperations.java` 44, 54, 62, 72, 82; `JGitOperations.java:134-141` |
| L1-2 | OBSERVED "JGit 7.8.0 has no command that adds a linked working tree": no class with `worktree` in its name under `org/eclipse/jgit/api` in the 7.8.0 sources jar | checked | confirms | the sources jar |
| L1-3 | Done-when "`git-worktrees` depends on `git-core` and on no other reactor module" is false as written: the POM declares `git-testing` in test scope (the same pattern as `git-core`) | checked | confirms | `git-worktrees/pom.xml:39` |
| L1-4 | Deliverable 4 asks to move the worktree part of `GitOperationsContractTest`; the client document records that the class tests only `GitCredential`, which no module takes, so nothing moves: the premise was stale, the resolution is documented | checked | confirms | `doc/clients/pm-mcp-core.adoc:115` |

Persisted onto the spec with `corpus set-verdict` (producer `libraries-initial-implementation/analyze`, at `e89cf86`): claims 0, 1, 2 and 3 corroborated.

**Layer 1 verdict:** findings — 1 minor (L1-3); 1 info (L1-4)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | pom, `module-info`, `GitWorktreesLogMessages`, `LinkedWorktrees` | `LinkedWorktreesTest` (329 lines) | none | checked |
| 2 | `JGitWorktreeOperations` 133 / 512 / 543 / 593 | `JGitWorktreeOperationsTest` (`@Nested` per operation, `hookRefused`, `postCommitHookRefused`, `filterDriverRefused`, `concurrentAdditions`, `linkedReflog`); the HEAD-reflog claim of the lane is **settled by the merged code**: a commit in a linked worktree runs without a reflog comment and appends its entries itself (`appendLinkedReflogs`, 666-679) to the linked admin directory's `logs/HEAD` and the common directory's branch log; `linkedReflog` asserts the main log unchanged | the narrative says one behaviour change; the class Javadoc says four, the diff has more (L2-1) | checked |
| 3 | `worktreeStatus` 702-720, `WorktreeStatus` | `WorktreeStatusTest`: exact sets per category, linked worktree, bare repository | none | checked |
| 4 | `doc/specification/git-worktrees.adoc`, `general.adoc`, `doc/clients/pm-mcp-core.adoc`, `AGENTS.md` | `GitWorktreeListCompatibilityTest` (gated by `assumeTrue(gitInstalled())`; it checks path, branch, head and detached for two added trees only, not locked or prunable state nor `git worktree remove`) | `general.adoc` outside the declared surface, explained by the Standing Constraints | checked |

Layer-2 findings:

- **L2-1 (minor).** The landing and the PR body name one deliberate behaviour change to the copy (the atomic claim of the administration directory). The diff changes at least five more: partial-file writes with an atomic move and `HEAD` last, a `locked: initializing` file that is removed at the end, `list()` skipping incomplete entries, relative-`gitdir` resolution (`LinkedWorktrees.java:133`), and the branch reservation. `LinkedWorktrees` itself says "four points" (line 49). The deliverable is fine; the narrative understates it, and the spec's "plain copy" framing is no longer a description of the code.
- **L2-2 (minor).** `AbsenceContractsTest` and most of `WorktreeOperationsRecordsTest` assert that an accessor returns what the constructor was given (`AbsenceContractsTest.java:44-60`, re-read). They cannot fail for a realistic regression. This is the same pattern the PLAN-02 analysis recorded for `git-core` (PLAN-05 deliverable 5 (b)).
- **L2-3 (minor).** The fixes the lane lists have gaps in their tests: no test of create-branch racing an existing-branch add of the same branch (L3-4), none of `worktreeList` / `worktreeRemove` / `commit` on a bare repository (L3-2), none of `worktreeAdd` with a linked-worktree root (L3-3), a path inside `.git` (L3-6) or a hostile branch name (L3-1), none of a failing `appendLinkedReflogs` (L3-5). `concurrentAdditions` covers the same target and two existing-branch adds only.
- **L2-4 (not shown by reading).** Whether the compatibility test ran in the lane (it is gated).
- **L2-5 (not shown by reading).** "500 tests in `verify -Ppre-commit`", "Sonar 0 at the head" and "weakened tests strengthened" need a run or a provider read.

**Layer 2 verdict:** findings — 3 minor (L2-1 to L2-3); 2 not shown by reading (L2-4, L2-5)

### Layer 3 — Correct

One major and eight minor defects; no blocker. The code is careful in the large (an atomic claim, a rollback with an ownership check, an identity check before a destructive removal, results mapped through `failure()`), and the defects are at its edges. Re-read by the orchestrator at `e89cf86`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `JGitWorktreeOperations.java:215` (`Constants.R_HEADS + branch`, no validation anywhere in `git-worktrees` or in the JGit ref update path), `307-311` | `worktreeAdd` with `createBranch=true` and branch `../../../x` (or a name with `..`, a space, a trailing `.lock`) | the ref is `refs/heads/../../../x`; `RefDirectory.fileFor` (1433-1436) resolves it under `refsDir` unvalidated, so `update()` writes the 41-byte ref file, and the parent directories, outside `refs/` (into `.git` or beyond), and the new worktree's `HEAD` becomes `ref: refs/heads/../../../x`. It cannot replace a non-ref file such as `config` (`exactRef` at 317 then fails). Reachable whenever the branch name comes from a caller such as an MCP tool. `git-core` already validates its refspecs with `Repository.isValidRefName` (`JGitRepositoryOperations.java:287`) | **major** |
| L3-2 | `JGitWorktreeOperations.java:517-538` (`main.getWorkTree()` at 524; the catch lists `IOException`, `IllegalArgumentException`, `JGitInternalException`), `546` | `worktreeList`, or `worktreeRemove` (which calls it), on a bare repository; `commit()` on a bare repository (`git.add()` reaches `lockDirCache`; its catch at 625 does not cover `NoWorkTreeException`, while `worktreeStatus` does, line 717) | an unchecked `NoWorkTreeException` escapes instead of a `GitResult` (GDR-GEN-3). The lane states the list case as an open item; it is confirmed and also covers remove and commit | minor |
| L3-3 | `JGitWorktreeOperations.java:504-509` (`checkedOut` reads `main`, opened at the given root, line 148) | `worktreeAdd` with `root` = a linked worktree, for a branch checked out in the real main worktree | no `BRANCH_CHECKED_OUT`: two worktrees on one branch, and the target may lie inside the linked tree. The contract says "the main working-tree root" and nothing enforces it; `worktreeList` resolves the main repository through the common directory for this reason | minor |
| L3-4 | `JGitWorktreeOperations.java:218-222` (reservation taken for the existing-branch path only), `LinkedWorktrees.java:84-91` (`HEAD` written last) and `135-137` (an entry without `HEAD` is skipped) | add A with `createBranch=true` on branch X; between its branch creation (line 311) and `LinkedWorktrees.create` writing `HEAD`, add B on existing branch X | B reserves, finds no worktree on X, and succeeds: two worktrees on one branch. The window is narrow, and the "listing tolerates an in-progress entry" fix is what opens it | minor |
| L3-5 | `JGitWorktreeOperations.java:616-619` (`command.call()` moves the branch and `HEAD`, then `appendLinkedReflogs`), `625` | a commit in a linked worktree whose `logs/HEAD` cannot be written | the commit exists but the result is `FAILED`; a retry gives `NOTHING_TO_COMMIT`, so the caller never learns the commit id. The reflog append is also neither locked nor atomic with the ref update, unlike JGit's `ReflogWriter` | minor |
| L3-6 | `JGitWorktreeOperations.java:137-141` (the guard is `startsWith(root)` and not the root) | `worktreeAdd` with path `<root>/.git/wt` or `<root>/.git/worktrees/x` | the add succeeds and creates a checkout and a nested administration area inside the git directory; the target is not compared with the common directory or other worktrees' roots | minor |
| L3-7 | `JGitWorktreeOperations.java:170` (rollback and mapping only for `IOException`, `JGitInternalException`, `IllegalArgumentException`), `352-363` (`deleteCreatedBranch` ignores the `RefUpdate` result), `LinkedWorktrees.java:180-188` | an unchecked exception from the attempt (`UncheckedIOException` from `Files.list` in `claimTarget` / `releaseTarget`, `IllegalStateException`, NPE), or a JVM crash | `rollBack` is skipped: the claimed target, the created branch and the reservation file stay, and the exception escapes. A stale `worktree-branch-*.lock` then returns `BRANCH_CHECKED_OUT` for that branch for good, with no way out in the API (the `reserveBranch` Javadoc admits the crash case); a `LOCK_FAILURE` in `deleteCreatedBranch` leaves the created branch silently | minor |
| L3-8 | `doc/specification/git-worktrees.adoc:148`, `WorktreeOperations.java:120`, `JGitWorktreeOperations.java:786-794` | a clean-filter driver reached by `StatusCommand` (whether JGit's status path runs it needs a run); `worktreeRemove` of another repository at the root (line 576), `worktreeAdd` of a branch at another commit (line 234) | the documented outcome list of `worktreeStatus` (`OK`, `NOT_A_REPOSITORY`, `FAILED`) omits `REFUSED_BY_POLICY`, which `failure()` returns for any refusal in the cause chain; `IDENTITY_MISMATCH` is documented in `general.adoc:106` and `GitOutcome` narrowly ("a pinned full commit id that reaches another commit") yet the operations return it for two other situations | minor |
| L3-9 | `JGitWorktreeOperations.java:553-554` (`worktreeRemove` compares a symlink alias with registered paths that are not real-path'd), `637-640` (`unstageLinkedRoots` compares the registered path with `getWorkTree().toRealPath()`) | the caller passes a different symlink alias than the one in `gitdir`; a git-created linked worktree registered through a symlinked path | remove ends `WORKTREE_NOT_FOUND` for a registered tree (fails safe, not destructive); in the second case the root is not unstaged and the gitlink is committed (not so for trees this library adds, whose path is canonical). A gitlink already committed at a linked-root path is removed from the index and the commit records the removal (649-651) | minor |

**Layer 3 not shown by reading:** whether JGit's status reports a nested linked root as untracked while `commit` never stages it (a tree whose only change is a linked root then reports untracked paths and yields `NOTHING_TO_COMMIT`), and whether `unionEqualsJGitUnion` exercises a main tree with a nested worktree; assume-unchanged / skip-worktree and submodule handling, inherited from JGit's `StatusCommand`; a submodule's contents in a removed worktree are not refused as git does. Log and detail content was read and shows no GDR-NFR-3 violation (INFO carries a commit id or nothing, WARN the capability name, failures the exception class).

**Layer 3 verdict:** findings — 1 major (L3-1); 8 minor (L3-2 to L3-9); 2 not shown by reading

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS: `module-info`, automatic module name | conforms | `module-info.java`; `pom.xml:19` sets the automatic module name |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | `package-info.java`; `@Nullable` on the `Worktree` and `AddAttempt` fields |
| `general.adoc` GDR-GEN-3 | Expected outcomes are results, not exceptions | deviates | a bare repository (L3-2) and an unchecked exception in the add (L3-7) escape as exceptions |
| `general.adoc` GDR-GEN-4 | `CuiLogger`, `LogRecord` holder, catalogue for every INFO/WARN/ERROR | conforms | INFO 1, INFO 2, WARN 100 in `git-worktrees/doc/LogMessages.adoc` |
| `general.adoc` GDR-NFR-3 | No secrets, person data or paths in logs and exception messages | conforms | INFO carries a commit id only; `failure()` returns the class name; the JUL-capture test can fail |
| `git-core.adoc` GDR-CORE-1 | The library never starts a process | conforms (conditional) | every repository is built on `RefusingFS`; hooks are checked up front; the Javadoc states the `SystemReader` condition; only the compatibility test starts `git` |
| AGENTS.md | Javadoc on every public type and method | conforms | the interface, records and constructor are documented; `LinkedWorktrees` is package-private |
| AGENTS.md | No dependency without approval | conforms | only approved libraries and `git-testing` in test scope; no EasyMock needed |
| `git-worktrees.adoc` GDR-WT-1 | Add, list and remove in git's own layout | deviates (defects) | layout files written, compatibility test exists (gated); branch-name validation (L3-1) and the races (L3-3, L3-4) are missing |
| `git-worktrees.adoc` GDR-WT-2 | Stage everything and commit; hooks refused | deviates (defects) | up-front hook check; success reported as `FAILED` after a reflog write error (L3-5); bare repository gives an exception (L3-2) |
| `git-worktrees.adoc` GDR-WT-3 | Three path sets and nothing else | conforms | `WorktreeStatus` has exactly `changed`, `untracked`, `deleted`; the documented outcome list is incomplete (L3-8) |
| CLAUDE.md | Verify JGit claims and name the version | conforms | 7.8.0 named; `RefDirectory.logFor`, `FS.findHook`, `CommitCommand` claims check out against the sources jar (the `FileTreeIterator` claim was not re-read) |
| CLAUDE.md | Do not touch `.github/project.yml` | conforms | not in the diff |
| `general.adoc` GDR-GEN-1 | `git-worktrees` depends on `git-core` only | conforms (test scope) | compile scope `git-core` only; the spec's Done-when wording does not allow the test-scope `git-testing` (L1-3) |

## Metrics and Anomalies

- Tokens: 13,415,441.
- Duration: 12,200 s (about 3.4 h).
- Anomalies: `effort` was never stamped (`unknown`); `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` are not recorded by any lifecycle step (`unknown`), so `inbox landing-check` reports `complete: false`. Both per-deliverable `manage-change-ledger append` calls failed on `--changed-paths-file` and succeeded with `--changed-paths` (lesson `2026-11-18-13-004`). The focused per-deliverable builds resolved as orchestrator-tier ("Unmeasured") and were skipped by the leaves for deliverables 1-3; envelope 2 ended with a `verification_handback` (lesson `2026-11-18-18-003`). All 13 dispatch-boundary rows carry no `step_id` (lesson `2026-11-18-07-006`). All 27 work-log warnings of the plan are one configured-disabled build-server line (lesson `2026-11-18-18-004`). `phase_handshake` returned `drift` after the lane resolved its own two outline Q-Gate findings (lesson `2026-11-18-18-002`).

## Routing and Merge Behavior

- Review: 15 CodeRabbit findings (14 inline / review-body / walkthrough, plus the GDR-NFR-3 vs GDR-WT-2 wording item `b121ed`, held by the operator with no change); 12 fixed in `3520316`, replies sent after CI passed on `a86a1ee`. Sourcery refused on a hard quota (optional bot). **Provider re-read:** `review-gate` for #30 returns `merge_allowed: false`, `undecidable_read` (the review-body section "Minor comments: 7" is unreadable). The landing's three review numbers are `unknown`, so there is no difference to record; `review_body_findings_handled` is **not re-verified** (the progress file was not read).
- CI/merge: merged through the merge queue as a squash, `e89cf86`, on the operator's explicit go-ahead (the repository rules say not to merge without it); no rebase conflict reported; PR state `merged`, merge commit read through the CI abstraction.
- Sonar: the landing claims `sonar_open_new_issues=0` (three `java:S5778` fixed). This analysis reads the quality gate of #30 as `OK` (new coverage 89.6 %, hotspots 100 % reviewed). The `search` verb lists the three `java:S5778` issues in `WorktreeOperationsRecordsTest` without a status column; the landing says they were fixed. The open count of 0 is **not re-verified**.
- Overlap with the parallel PLAN-04 (`AGENTS.md`, `doc/README.adoc`): not yet observable; PLAN-04 is still running.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-03 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-03 --field pr --value 30`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-03 --field landing --value landings/PLAN-03.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-03 --field plan_marshall_plan_id --value plan-03-git-worktrees-import`
- [x] epic.md narrative reconciled (PLAN-03 started and shipped; the lesson dispositions; Open Defects; Watches)
- [x] layer-1 verdicts persisted onto the spec (claims 0-3 corroborated)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **Layer-3 defects of `git-worktrees` (L3-1 to L3-9) had no staged spec to join.** Reported to the operator in the turn that recorded them; the operator chose to stage a hardening plan, now `PLAN-19-git-worktrees-hardening` (4 deliverables, depends on PLAN-03). L3-1 (branch-name validation) is the one a caller can trigger with input.
- Incomplete landing: `inbox landing-check` reports `complete: false` (missing `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`); recorded in the same Open Defect as the landings of PLAN-01, PLAN-02 and PLAN-18.
- Lessons: the three tooling lessons of the first group and the three recurrences joined `2026-11-18-18-001` to `-004`, `2026-11-18-13-004` and `2026-11-18-07-006`; the twelve lessons about this repository's own worktree code (004-015) were discarded as shipped, their rules being embodied in the merged code, tests and specification.
- The note in the PLAN-03 spec "May run beside PLAN-04 (disjoint apart from `AGENTS.md`)" was corrected earlier to the two shared files; the actual collision, if any, shows at PLAN-04's landing.
