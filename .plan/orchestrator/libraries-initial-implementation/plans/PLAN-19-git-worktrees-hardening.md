# PLAN-19: git-worktrees — hardening of the taken-over operations (leftovers of PLAN-03's landing analysis)

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Close the defects the three-layer analysis of PLAN-03 (PR #30, merge commit `e89cf86`) found in `git-worktrees`: an
unvalidated branch name that `worktreeAdd` turns into a ref path (one major defect), bare repositories and unchecked
exceptions that escape the result contract (GDR-GEN-3), roots and targets the add does not guard, two races and a
rollback gap, a commit reported as failed after it exists, and the tests and documented outcomes that do not say what
the code does. The analysis read the code only (no build, no test run); every item is first reproduced by a test that
fails at the plan's base and passes at its head, or recorded with its reason as an accepted exception in
`doc/specification/git-worktrees.adoc`.

## Deliverables

1. Input guards of `worktreeAdd` (L3-1, L3-3, L3-6): the branch name is checked with `Repository.isValidRefName` on
   `refs/heads/{name}` before anything is claimed and a refused name ends with a `GitOutcome` the same way `git-core`
   ends an invalid refspec (`JGitRepositoryOperations`, the refspec check) — if no existing constant fits, the
   addition follows the rule of the GDR-GEN-3 table in `doc/specification/general.adoc` and is named in the PR; the
   `root` must be the main working-tree root (a linked-worktree root is refused or resolved to the main repository
   through the common directory, as `worktreeList` does); the target is refused when it lies inside the common
   directory, inside the administration area, or inside another registered worktree.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
2. Result contract (L3-2, L3-7, L3-5, L3-8): `worktreeList`, `worktreeRemove` and `commit` on a bare repository return
   a `GitResult` instead of throwing `NoWorkTreeException`; an unchecked exception from an addition rolls back what the
   attempt claimed (target, created branch, reservation) and ends as a result; the result of `deleteCreatedBranch` is
   read; a commit in a linked worktree whose reflog append fails still reports the commit it created (the reflog write
   is made part of the guarded step, or its failure is reported without losing the commit id — the choice is recorded);
   the documented outcome lists of `worktreeStatus` (`REFUSED_BY_POLICY`) and of the operations that return
   `IDENTITY_MISMATCH` for other situations than the GDR-GEN-3 row names are corrected in
   `doc/specification/git-worktrees.adoc`, `doc/specification/general.adoc` and the Javadoc.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
3. Races and path identity (L3-4, L3-9): the branch of a NEW-branch addition is reserved like an existing branch is, so
   no addition on that branch passes the occupancy check between the creation of the branch and the registration of its
   `HEAD`; a reservation left by a crashed process has a documented way out (an owner decision if it needs new public
   API: put to the owner through the question tool BEFORE it is added); registered worktree paths are compared as real
   paths in `worktreeRemove` and `unstageLinkedRoots`, so a symlink alias neither hides a worktree nor lets a gitlink be
   committed.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests (L2-2, L2-3, L2-4, and the "not shown by reading" items of the analysis): a test per item above that fails at
   the base (hostile branch names `../../../x`, a name with a space, a trailing `.lock`; a bare repository for list,
   remove and commit; a linked-worktree root in add; a target inside `.git`; create-branch racing an existing-branch
   add; an unchecked exception in the add; a failing reflog append; a symlinked worktree path); the tautological
   accessor assertions of `AbsenceContractsTest` and `WorktreeOperationsRecordsTest` replaced by assertions that can
   fail; the compatibility test extended by `locked` / `prunable` state and `git worktree remove` of an added tree
   (still skipped without a Git binary); two behaviours the analysis could not show by reading are settled by a test or
   recorded: a nested linked root of the main working tree in `worktreeStatus` against `commit`
   (untracked-but-`NOTHING_TO_COMMIT`), and a clean-filter driver reached by `StatusCommand`.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, module name `de.cuioss.git.document.<suffix>` repeated as the
  automatic module name), `@NullMarked` packages with `@Nullable` where needed, results instead of exceptions for
  expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a documented log-message
  catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and method, license header
  via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the `git-testing` fixtures and
  without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address, person name or filesystem
  path in a result detail, exception message, log message or commit metadata; tests assert it with a JUL log capture
  (`cui-test-juli-logger`) and with checks of result details.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call
  `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved on 2026-11-17: JGit 7.8.0, `cui-test-generator`,
  JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through
  the question tool BEFORE it is added, with the alternatives considered.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- Behaviour changes of the public API (a new outcome, a refused input that was accepted) are stated in the Javadoc, in
  `doc/specification/git-worktrees.adoc` and in the PR; `pm-mcp-core` has no consumer of `git-worktrees` yet, so no
  compatibility shim is added.
- A test that starts a `git` process does so from the test only; the library itself never starts a process
  (GDR-CORE-1).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is a
  blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected;
  an `undecidable` read blocks.

## Done when

- `verify` passes at the plan's head, and every item of deliverables 1 to 3 has a test that fails at the plan's base
  (`e89cf86` or the head of `main` when the plan starts) and passes at its head, or is recorded as an accepted exception
  with its reason in `doc/specification/git-worktrees.adoc`.
- `worktreeAdd` with branch `../../../x` and `createBranch=true` creates no file outside `refs/` and ends with a
  documented outcome; `worktreeList`, `worktreeRemove` and `commit` on a bare repository return a `GitResult`.
- The documented outcome lists of `doc/specification/git-worktrees.adoc` and of the Javadoc name every outcome the
  operations can return.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `worktreeAdd` builds the branch ref from the caller's string without validation —
  `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:215`
  (`Constants.R_HEADS + branch`) at `e89cf86`, and `git-worktrees/src/main` has no `isValidRefName`; `git-core` uses
  `Repository.isValidRefName` for refspecs at `JGitRepositoryOperations.java:287`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e89cf86: JGitWorktreeOperations.java:215 'Constants.R_HEADS + branch' with no isValidRefName in git-worktrees/src/main; git-core JGitRepositoryOperations.java:287 uses Repository.isValidRefName (also unchanged at e2aced9)
- HYPOTHESIS: JGit 7.8.0 maps `refs/heads/../../../x` to a file outside `refs/` and creates its parent directories —
  confirm/refute at JGit `v7.8.0.202609011348-r` `org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/RefDirectory.java`
  § `fileFor` (lines 1433-1436: `new File(refsDir, name)`, read) and `LockFile.java` § `lock` (`FileUtils.mkdirs` of the
  parent, not re-read) — and by the deliverable 4 test (verify-at-outline).
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: JGit 7.8.0 RefDirectory.fileFor (1433-1436) is new File(refsDir, name) with no validation, and LockFile.lock (172) calls FileUtils.mkdirs of the lock's parent (both read by the orchestrator in the sources jar); the PLAN-19 test invalidBranchNameRefused asserts no file at the escape path
- OBSERVED: `worktreeList` reads `main.getWorkTree()` (`JGitWorktreeOperations.java:524`) and its catch lists
  `IOException`, `IllegalArgumentException` and `JGitInternalException` (537); `worktreeRemove` calls it (546);
  `commit` does not catch `NoWorkTreeException` (625) while `worktreeStatus` does (717) — at `e89cf86`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e89cf86 (PLAN-03 analysis, landings/PLAN-03.md): worktreeList main.getWorkTree and its three-type catch, worktreeRemove calls it, commit lacks NoWorkTreeException while worktreeStatus has it
- OBSERVED: the branch reservation is taken only for an existing branch (`JGitWorktreeOperations.java:218-222`), the new
  branch is created at 311, and `LinkedWorktrees.create` writes `HEAD` last (`LinkedWorktrees.java:84-91`) while
  `list` skips an entry without `HEAD` (135-137) — at `e89cf86`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e89cf86 (PLAN-03 analysis): reservation only for an existing branch, LinkedWorktrees.create writes HEAD last, list skips an entry without HEAD
- OBSERVED: `commit` in a linked worktree appends the reflog after `command.call()` inside the same try
  (`JGitWorktreeOperations.java:616-619`), whose `IOException` ends as `FAILED` (625) — at `e89cf86`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e89cf86 (PLAN-03 analysis): commit appends the reflog after command.call() inside the same try, whose IOException ends FAILED
- OBSERVED: the target guard is `startsWith(root) && !equals(root)` (`JGitWorktreeOperations.java:139`);
  `checkedOut` reads the repository opened at the given root (504-509); `worktreeRemove` and `unstageLinkedRoots`
  compare registered paths that are not real-path'd (553-554, 637-640); the rollback catch covers three exception
  types (170) and `deleteCreatedBranch` ignores the `RefUpdate` result (352-363) — at `e89cf86`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e89cf86 (PLAN-03 analysis): target guard startsWith(root) && !equals(root); checkedOut reads the repository at the given root; unresolved registered paths; three-type rollback catch; deleteCreatedBranch ignores the RefUpdate result
- HYPOTHESIS: JGit 7.8.0 `StatusCommand` reports a nested linked root as untracked, while `AddCommand` plus
  `unstageLinkedRoots` never stages it, so the tree reports untracked paths and `commit` gives `NOTHING_TO_COMMIT` —
  confirm/refute by the deliverable 4 test (verify-at-outline); and that a clean-filter driver is run by `StatusCommand`
  (then `REFUSED_BY_POLICY` is a documented outcome of `worktreeStatus`).
  - verdict: unverifiable | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: both clauses are JGit run behaviours; PR #31 pins them by tests nestedLinkedRootStatusAgainstCommit and cleanFilterRefused (WorktreeStatusTest) and records them in git-worktrees.adoc, but whether they hold is a run result, not shown by reading
- Verify-first clause: the analysis read the code only (no build, no test run); every OBSERVED claim above is re-read at
  the plan's own HEAD at outline, and a refuted one is dropped from the item that names it.
  - verdict: unverifiable | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: a process commitment of the lane (re-read at outline); the merged code does not show whether the outline re-read, the progress file was not read; the OBSERVED claims it names held at e89cf86 (claims 0, 2-5)

## Expected Surface

- OBSERVED: `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `git-worktrees/src/test/java/de/cuioss/git/document/worktrees/`
- OBSERVED: `doc/specification/git-worktrees.adoc`
- OBSERVED: `git-worktrees/doc/LogMessages.adoc` (only if a log identifier is added or changed)
- HYPOTHESIS: `doc/specification/general.adoc` (the `IDENTITY_MISMATCH` and outcome wording of the GDR-GEN-3 table; a new
  outcome, if none fits the refused branch name)
- HYPOTHESIS: `git-core/src/main/java/de/cuioss/git/document/core/GitOutcome.java` (only if a new outcome is needed; then
  PLAN-04 and PLAN-05, which edit the git-core module, are sequenced first or the change is named in both PRs)

## Dependencies and Sequencing

- Depends on: PLAN-03 (landed as PR #30, merge commit `e89cf86`).
- Overlaps with: PLAN-04 (`doc/specification/general.adoc`, and `GitOutcome.java` if an outcome is added) — one shared
  file does not block; a shared `GitOutcome.java` is sequential. Disjoint from PLAN-05 and PLAN-06 apart from the
  possible `GitOutcome.java` (PLAN-06 touches `git-worktrees/pom.xml` and resources, not the code).
- Adjacent to: `git-core/` — consumed, not changed, unless the new outcome of deliverable 1 is needed.
- Not a prerequisite of PLAN-06 or any later plan; it may land before or after them.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-19-git-worktrees-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
