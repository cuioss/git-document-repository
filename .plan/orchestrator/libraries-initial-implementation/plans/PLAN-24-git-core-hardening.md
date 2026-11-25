# PLAN-24: git-core and git-testing hardening (the leftovers of PLAN-20)

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fix the defects, test gaps and documentation gaps that the three-layer analysis of PLAN-20 (`landings/PLAN-20.md`, PR #69,
`f7eda4e`) found in `git-core` (its worktrees package included) and `git-testing`, and that no staged plan owns. This is the
container plan of the epic for those two modules: it was staged on 2026-11-24 under the epic's routing rule (a finding goes into
the staged plan that fits by content, and a container plan per module is staged when none fits or the fitting one holds more than
ten deliverables), because no staged plan edits `git-core` or `git-testing`. Later analyses fold their `git-core` and
`git-testing` leftovers into this spec until it is launched.

## Deliverables

1. `git-core` defects of PLAN-20's landing (`landings/PLAN-20.md` L3-1 to L3-5, L1-1 and L1-3), each first re-read at the plan's
   head, behaviour fixes reproduced by a test that fails at `f7eda4e` (lines are those of `f7eda4e`): (a) **`InCoreWriter.checkPaths`
   (245-255) applies the strict `ObjectChecker` (`setSafeForWindows`, `setSafeForMacOS`) to every edit, `TreeEdit.Delete` included**,
   so a parent tree that already holds `aux.txt`, `trailing.` or `a:b` (stored by a push from Linux) can no longer be edited by a
   `Delete` of that name (`NOT_FOUND` "edits: invalid path", nothing written), which the library could do before PLAN-20: check
   the portability of a created name only (a `Put`, and the target of a rename if one exists), never of a `Delete`; the owner
   decision recorded in `git-core.adoc` (262-266) covers creating names, so record the narrowing there, tested with a parent tree
   built from a Linux-valid name that is Windows-invalid and a `Delete` of it, through `InCoreWriter` and `compareAndSwap`; (b) the
   duplicate, case-fold and overlap check compares the edits with each other and never with the parent tree (re-read first: the
   point is leaf-reported), so `Put("README")` onto a tree that holds `readme` writes two entries that collide on HFS+ and NTFS,
   while `Delete("a")` with `Put("a/b")` (a file converted into a directory) is refused as overlapping: decide, with the owner
   where it changes a documented behaviour, whether the check also covers the parent tree, allow a `Delete` of a path with a
   `Put` below it, and test both; (c) `JGitWorktreeOperations.canonical` (1012) treats every `FileSystemException` as a missing
   segment and moves to the parent, so `AccessDeniedException` and `FileSystemLoopException` resolve through the ancestor: a path
   behind a symlink loop or an unreadable directory can compare equal to the parent-resolved one in `worktreeRemove` (733) and the
   target guard: catch `NoSuchFileException` (and `NotDirectoryException`) only, return a refusal for the others, tested with a
   symlink loop and a directory without read permission; (d) `JGitRepositoryOperations.storedOrigin` (373) returns empty for every
   URL JGit parses as a path starting `//` (`isUnparsedAuthority`, 1230), so `cloneIfMissing` with a local policy and
   `//server/share/repo.git` ends `REFUSED_BY_POLICY` "not a URL" where it was accepted, although the Javadoc and the
   specification name only the uppercase-scheme case: accept the POSIX and UNC form (storing nothing that carries a credential), or
   record the refusal in `git-core.adoc`, `general.adoc` and `doc/clients/pm-mcp-core.adoc`, tested; (e)
   `JGitWorktreeOperations.isBranchName` (610) refuses any name starting `refs/` and the name `@`, which git accepts, so a branch
   `refs/foo` git would create is refused and a reservation file left earlier for `HEAD` or `refs/x` cannot be released through
   `releaseBranchReservation`: let the release path remove a reservation whose name the add path would now refuse, or record the
   limit; the refusal itself is a documented library rule and stays unless the owner says otherwise; (f) the Done-when of PLAN-20
   demanded a failing-at-base test for every behaviour fix and its deliverable 3(c) (`Integer.MAX_VALUE` attempts) cannot have one:
   record in `git-core.adoc` § GDR-CORE-5 that the overflow fix is verified by reading, and keep `maximalAttempts`
   (`ReferenceUpdaterTest` 1087) as the documented regression guard; and whether the owner was asked for the bound of
   `readFile` (deliverable 4(b) of PLAN-20 required it through the question tool, the specification gives a reason only): ask now
   and record the decision.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
2. Tests, fixtures and documents of PLAN-20's landing (`landings/PLAN-20.md` L2-1 to L2-6, L3-6 to L3-9 are verified
   behaviour and need no change), each first re-read at the plan's head: (a) no test passes `HEAD`, or any one-component name, to
   `compareAndSwap` or `compareAndDelete` (`ReferenceUpdaterTest`; only `..` is pinned), so a change that allowed `HEAD` (the
   declined CodeRabbit comment, decision D3(d)) would pass the suite: add the test; (b) `uncheckedRollbackStepContinues`
   (`JGitWorktreeOperationsTest` 1999) tests only the package-private `attempt()` with lambdas and never the wiring
   `rollBack` to `attempt` for `releaseTarget`, `deleteCreatedBranch` and `releaseReservation` (463-478): make each step
   throw an unchecked exception through a repository or file-system double and assert that the later steps still run;
   (c) `cloneStoresNoQueryData` (`JGitRepositoryOperationsTest` 1351) uses a `file:` remote, where the base fails on the query
   path before it stores a token, so the property "no query data in the stored configuration" is shown for no https transport:
   add an https-shaped case through the intercepting transport protocol; (d) the hermetic fixture
   (`HermeticGitConfigurationExtension`) has one user, `RedirectEndToEndTest`, while other tests still read the host
   configuration (the hooks path pinned by hand in `pinEmptyHooks`, `core.autocrlf` per test): read which tests depend on the
   host, apply the fixture to them, and record the list in `git-testing.adoc`; (e) `AGENTS.md` "Current state" omits the public
   constant `JGitRepositoryOperations.READ_FILE_LIMIT_BYTES` (212) and the `readFile` bound (`grep -c` of the name at `f7eda4e` is
   0): add it; (f) the negative runs that prove the new `direct-dependency-isolation` execution are recorded as prose ("run once on
   2026-11-23 and not committed") and the repeat command is `./mvnw validate -pl ...`, not one of the canonical commands: record
   the repeat command in the canonical form or as a committed check; (g) the `NativeImageMetadataTest` JSON reader (243) is a
   200-line hand-written recursive-descent parser with no depth bound: bound it or use the module's existing means, noting that
   its input is the module's own two files; (h) the `.GIT` and `git~1` merger test the PLAN-20 spec asks for is inferred only
   from the same mapping in `InCoreWriterTest` (the `InCoreMergerTest` diff was not read): check that it exists and add it if not.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
3. The classification of a lost lease (`landings/PLAN-22.md` L3-3 and the `git-core` half of L3-4; folded in on 2026-11-25 under the routing rule,
   because PLAN-24 is the container plan for `git-core`), first re-read at the plan's head (lines are those of `c22700a`): `JGitRepositoryOperations`
   (200-210, 960-962, 1010-1013) tells a lost lease from other failures by the exact text of the remote, `failed to lock` (JGit `ReceivePack`) or
   `incorrect old value provided` (native Git, observed with Git 2.56.1 only), and treats every other wording as a permanent `REJECTED`, so a hosted
   remote that words a lost race otherwise, or an older native Git, loses the retry; and `failed to lock` is also what JGit answers for a stale or
   permanent `.lock` file on the remote, so that case is `LEASE_REFUSED` and is retried in `git-store` up to `save.max-attempts`. The limit is an
   accepted owner decision of 2026-11-24 (`git-core.adoc` 520-527), so this deliverable first puts it to the owner again with the evidence and decides:
   keep it and extend the recorded wordings with the ones a test can script (`failed to update ref`, the reftable backend, a second native Git version),
   or tell a stale lock from a lost race where the remote's text allows it; tested against the scripted receive side (`RefusingReceiveProtocol`) for
   every recognised wording and one unrecognised, and record the result in `git-core.adoc`, `general.adoc` and `doc/clients/pm-mcp-core.adoc` in the
   same commit. The pause between retries is PLAN-25 deliverable 2(a).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: implementer

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked`
  packages with `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc
  on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock
  against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail,
  exception message, log message, stored configuration or commit metadata.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Every behaviour fix is first reproduced by a test that fails at `f7eda4e` and passes at the plan's head; an item whose premise
  the re-read at outline refutes is dropped from the deliverable that names it. A test of a guard pins every setting that could
  stop the run before the guard is reached, takes fixture content that is distinct by construction (`DistinctTextGenerator`
  of PLAN-20), and fails on the regressed code.
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn`
  ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit 7.8.0, `cui-test-generator`, JUnit 5, EasyMock,
  `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through the question tool BEFORE it
  is added.
- No new `GitOutcome` constant without the owner's approval: an invalid name reuses `REFUSED_BY_POLICY`. A changed outcome of a
  public operation is updated in the Javadoc, the specification table, `general.adoc` and `doc/clients/pm-mcp-core.adoc` in the
  same commit, and the "Current state" paragraph of `AGENTS.md` when a public type or method changes.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- `git-store`, `git-forms` and the other aspect modules stay read-only; an item that needs a change there is reported in the PR
  and the inbox message, not absorbed.
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S
  SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The
  lane never records a dismissal of its own; a dismissal the run cannot transmit is a blocker. The merge gate reads the
  open-finding count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks.
- Review lessons of PLAN-20 (`landings/PLAN-20.md`; lessons `2026-11-24-07-004` to `-008`): when a deliverable corrects a sentence,
  content-search every document for it and list every hit; a standing cross-document constraint of the request is applied to every
  deliverable's affected files before the Q-Gate; a dependency or an enforcer change is paired with the GDR-GEN-1 list in
  `general.adoc` and `AGENTS.md`; a deliverable that says "every file of kind X" enumerates the result; re-sync with the base
  before the head-bound review steps run, not at `branch-cleanup`.

## Done when

- `verify` passes on the reactor at the plan's head, and every behaviour fix of deliverable 1 has a test that fails at
  `f7eda4e460d6c086238900309baa3513ea80b084` (`main` after PLAN-20, where the defects are present) and passes at the plan's head;
  an item whose premise the re-read at outline refutes is dropped from the deliverable.
- Every item of deliverables 1 and 2 is either fixed and tested at the plan's head, or recorded as an accepted exception or an
  owner decision with its reason in `doc/specification/git-core.adoc` or `doc/specification/git-testing.adoc`; no document lists an
  outcome the code does not return.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is `0`
  or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `InCoreWriter.checkPaths` runs the strict `ObjectChecker` over every edit including a `TreeEdit.Delete` — read at
  `git-core/src/main/java/de/cuioss/git/document/core/InCoreWriter.java` 245-255 at `f7eda4e`.
  - verdict: corroborated | checked_at: f7eda4e460d6c086238900309baa3513ea80b084 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at f7eda4e: InCoreWriter.checkPaths 245-255 builds one ObjectChecker with setSafeForWindows and setSafeForMacOS and calls checkPath(edit.path()) for every element of edits, Delete included
- OBSERVED: `JGitWorktreeOperations.canonical` catches `FileSystemException` and moves to the parent for every subtype — read at
  `git-core/src/main/java/de/cuioss/git/document/core/worktrees/JGitWorktreeOperations.java` 1012-1020 at `f7eda4e`.
  - verdict: corroborated | checked_at: f7eda4e460d6c086238900309baa3513ea80b084 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at f7eda4e: JGitWorktreeOperations.canonical 1012-1020 catches FileSystemException and moves to the parent
- OBSERVED: `JGitRepositoryOperations.storedOrigin` returns empty for an unparsed authority (`//host/path`) and
  `JGitWorktreeOperations.isBranchName` refuses a `refs/` prefix and `@` — read at `JGitRepositoryOperations.java` 373 and
  `JGitWorktreeOperations.java` 610-616 at `f7eda4e`.
  - verdict: corroborated | checked_at: f7eda4e460d6c086238900309baa3513ea80b084 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at f7eda4e: JGitRepositoryOperations.storedOrigin 373 returns Optional.empty for isUnparsedAuthority; JGitWorktreeOperations.isBranchName 610-616 refuses HEAD, @, a leading dash and any refs/ prefix
- OBSERVED: no test passes `HEAD` to `ReferenceUpdater` (`grep HEAD` over `ReferenceUpdaterTest` at `f7eda4e` is empty) and
  `AGENTS.md` does not name `READ_FILE_LIMIT_BYTES` (`grep -c` is 0; the constant is at `JGitRepositoryOperations.java` 212).
  - verdict: corroborated | checked_at: f7eda4e460d6c086238900309baa3513ea80b084 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at f7eda4e: grep HEAD over ReferenceUpdaterTest is empty; grep -c READ_FILE_LIMIT_BYTES AGENTS.md is 0 and the constant is at JGitRepositoryOperations.java 212

- OBSERVED: a lost lease is told from other failures by the exact remote text `failed to lock` or `incorrect old value provided` and every other wording
  is a permanent `REJECTED` — read at `git-core/src/main/java/de/cuioss/git/document/core/JGitRepositoryOperations.java` 196-212 and 1008-1014 at `c22700a`.
  - verdict: corroborated | checked_at: c22700ad81fd222b00887fc39cd98428b0392a40 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at c22700a: JGitRepositoryOperations 196-212 declares REMOTE_LOCK_FAILURE failed to lock and REMOTE_STALE_OLD_VALUE incorrect old value provided; 1008-1014 maps REJECTED_OTHER_REASON to LEASE_REFUSED only when expectedRemoteHead is set and lostRace(message) holds, else REJECTED

## Expected Surface

- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- OBSERVED: `git-core/src/test/java/de/cuioss/git/document/core/`
- OBSERVED: `git-testing/src/main/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-testing/src/test/java/de/cuioss/git/document/testing/`
- OBSERVED: `git-core/pom.xml`
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `doc/specification/git-testing.adoc`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/clients/pm-mcp-core.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `git-core/doc/LogMessages.adoc` (only if a log message is added)

## Dependencies and Sequencing

- Two deliverables, below the scope-bloat guard (about six).
- Collection plan: **hold it back from `next` until the operator releases it.** `analyze` folds further `git-core` and
  `git-testing` leftovers into this spec in the same edit as its Expected Surface, under the epic's routing rule; the operator
  releases it by naming it in a `next` round.
- Depends on: PLAN-20 (shipped, PR #69, `f7eda4e`). It edits `AGENTS.md` and `general.adoc`, which every other plan edits, so the
  gate puts it to the operator against any plan in flight that declares them.
- Adjacent to: `git-store` (PLAN-22) — consumed, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-24-git-core-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
