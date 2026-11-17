# PLAN-02: git-core — take over hardening, remote policy, identity, results and repository operations

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fill `git-core` with the generic part of `pm-provider-git` (plain copy from `~/git/pm-mcp-core` at the commit named in
the commit message, no history): `RefusingFS` with `CapabilityRefusedException` (GDR-CORE-1), `RemotePolicy`
(GDR-CORE-2), `GitIdentity` (GDR-CORE-11), `GitResult` / `GitOutcome` (GDR-GEN-3), and from `GitOperations` /
`JGitOperations` the repository part as `RepositoryOperations`: open and init, the bare clone of a remote
(GDR-CORE-3), log, fetch and push with credentials and lease (GDR-CORE-7), and the cache repository (GDR-CORE-8).
The worktree operations stay out (PLAN-03). Record the plain-copy decision in the documentation that still says "with
history".

## Deliverables

1. Root/module poms and `module-info.java` for JGit (owner-approved), the package `de.cuioss.git.document.core`, the
   `LogRecord` holder and log-message catalogue of `git-core`. `RefusingFS` + `CapabilityRefusedException` and
   `RemotePolicy` moved with `RefusingFSTest` and `RemotePolicyTest`, package-renamed from
   `de.planmarshall.provider.git`.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. `GitResult` and `GitOutcome` reconciled with the closed outcome list of GDR-GEN-3 (success, conflict, rejected,
   lease refused, unreachable, credentials expired or invalid, refused by policy, not found, unserved, ambiguous) and
   the outcomes the moved operations raise — keep the moved list "and extend it by the outcomes the requirements name"
   (`doc/clients/pm-mcp-core.adoc` § What Moves Where). Worktree-only outcomes (`WORKTREE_*`, `BRANCH_CHECKED_OUT`) are
   either kept in `GitOutcome` (spec: "`GitOutcome` (`git-core`), the outcomes of `git-worktrees` included") or the
   spec is corrected — decide at outline and make the spec and the code agree. `GitIdentity` with `GitIdentityTest`
   (GDR-CORE-11).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
3. `RepositoryOperations` (interface) and its JGit implementation: open, init, clone-when-missing of a bare clone with
   the mirroring fetch refspec and a caller-supplied push refspec (GDR-CORE-3), log, fetch and push with a
   `CredentialsProvider` per call and lease (GDR-CORE-7, distinct outcomes for rejected / lease refused / unreachable /
   credentials expired or invalid / refused by policy without contact), every repository built on `RefusingFS`.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging, pm-dev-java:javadoc
   - Persona: implementer
4. Cache repository (GDR-CORE-8: `initCache`, `fetchCommit` with unserved, `readFile` with not found) with
   `JGitCacheRepositoryTest` and `UnservedWantTest`; the repository part of `JGitOperationsTest`,
   `GitOperationsContractTest`, `GitOperationsRecordsTest` and `AbsenceContractsTest` split out of the moved tests
   (the worktree part goes to PLAN-03 — record the split in `doc/clients/pm-mcp-core.adoc`, closing its open
   question 1 for the git-core side). Tests use the `git-testing` fixtures (local repository, local remote).
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
5. Documentation: `doc/concepts/module-structure.adoc` § Order and `doc/clients/pm-mcp-core.adoc` (§ What Moves Where,
   § Order of the Switch-Over, § Open Questions) changed from "with history" to the plain-copy decision (owner,
   2026-11-17) and recording that the code is released under the Apache License 2.0 (open question 2 closed);
   `doc/specification/git-core.adoc` and `AGENTS.md` state updated.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, module name `de.cuioss.git.document.<suffix>` repeated as the
  automatic module name, `exports` added with the first public class of a package), `@NullMarked` packages with
  `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier
  (GDR-GEN-4), Javadoc on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 /
  `cui-test-generator` / EasyMock against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result
  detail, exception message, log message or commit metadata; tests assert it with a JUL log capture
  (`cui-test-juli-logger`) and with checks of result details.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call
  `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved on 2026-11-17: JGit 7.8.0, `cui-test-generator`,
  JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through
  the question tool BEFORE it is added, with the alternatives considered, and the spec document's "open" statement
  is replaced by the decision.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- When a module gains code, update its state in `doc/concepts/module-structure.adoc` (State column), the
  "Current state" paragraph of `AGENTS.md`, and the "planned / not yet implemented" wording of its specification
  document. When a module is created, add it to the root `pom.xml` `<modules>`, to the module table of `AGENTS.md`,
  and to the layering guard of PLAN-06 (GDR-GEN-1).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is a
  blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected;
  an `undecidable` read blocks.
- No PM-MCP term survives in a public type (GDR-GEN-1): `WorktreeShaDigest`, `JGitSourceRepository` and the
  `git.worktree-sha` framing stay in `pm-mcp-core` and are NOT copied.

## Done when

- `verify` passes at the plan's head with GDR-CORE-1/2/3/7/8/11 each covered by at least one test that names the
  requirement id, and no test reaching the network.
- `GitOutcome` and the GDR-GEN-3 table in `doc/specification/general.adoc` list the same outcomes.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the source module holds `RefusingFS` (127 lines), `RemotePolicy` (65), `GitIdentity` (165), `GitResult`
  (62), `GitOutcome` (63), `GitOperations` (360) and `JGitOperations` (726) — read at
  `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/main/java/de/planmarshall/provider/git/` at pm-mcp-core
  `287e1a86`.
- OBSERVED: `GitOperations` declares `open`, `worktreeSha`, `worktreeAdd`, `worktreeList`, `worktreeRemove`, `commit`
  (from a working tree), `log`, `fetch`, `push`, `initCache`, `fetchCommit`, `readFile` — and no separate `init`
  method — read at `GitOperations.java` § `GitOperations`.
- OBSERVED: `GitOutcome` today is `OK, NOT_A_REPOSITORY, UNKNOWN_REVISION, NOTHING_TO_COMMIT,
  REJECTED_NON_FAST_FORWARD, REJECTED_LEASE, REMOTE_ORIGIN_MISMATCH, CAPABILITY_MISSING, ALREADY_EXISTS,
  BRANCH_CHECKED_OUT, WORKTREE_NOT_FOUND, WORKTREE_DIRTY, WORKTREE_LOCKED, GIT_IDENTITY_AMBIGUOUS,
  GIT_IDENTITY_MISMATCH, PATH_REFUSED, PATH_NOT_FOUND, UNAVAILABLE` — read at `GitOutcome.java` § `GitOutcome`; it does
  not match the GDR-GEN-3 list name for name (e.g. no conflict, no credentials outcome).
- OBSERVED: `CapabilityRefusedException` is a nested class of `RefusingFS` extending JGit's `JGitInternalException`,
  and `RefusingFS` extends `FS_POSIX`, not `FS` — read at `RefusingFS.java` § `RefusingFS`.
- OBSERVED: `GitIdentity` refuses an abbreviated commit id "rather than widened to the one object it may" name — read
  at `GitIdentity.java` § class Javadoc.
- OBSERVED: the source module logs nothing (no `CuiLogger` / `LogRecord` in its main tree) — grep of the main tree at
  `287e1a86`.
- HYPOTHESIS: the bare-clone-when-missing of GDR-CORE-3 has no counterpart in `JGitOperations` (only `initCache` +
  `fetchCommit` for a single commit) and is new code — confirm/refute at `JGitOperations.java` § `initCache` /
  `fetch` (verify-at-outline).
- Verify-first clause: GDR-CORE-11 says the library "must provide full and abbreviated commit ids"; the moved
  `GitIdentity` refuses abbreviations as identities. Settle at outline whether "provide" means producing an
  abbreviation (compatible) or resolving one (a change), and make code and spec agree.
- Verify-first clause: GDR-CORE-1 says `CapabilityRefusedException` is "an exception type of `git-core`, not of JGit";
  the moved type extends `JGitInternalException`. Settle whether it must stay unchecked-under-JGit so JGit propagates
  it through `FS` callers (likely), and phrase the spec accordingly.

## Expected Surface

- OBSERVED: `pom.xml`
- OBSERVED: `git-core/pom.xml`
- OBSERVED: `git-core/src/main/java/module-info.java`
- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- HYPOTHESIS: `git-core/src/test/java/` (new test tree)
- HYPOTHESIS: `git-core/src/main/resources/` (log-message catalogue location, if beside the code is a resource)
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `doc/clients/pm-mcp-core.adoc`
- OBSERVED: `AGENTS.md`

## Dependencies and Sequencing

- Depends on: PLAN-01 (JGit in dependency management, the repository fixtures).
- Overlaps with: PLAN-03 (`doc/clients/pm-mcp-core.adoc`, root `pom.xml`, `AGENTS.md`), PLAN-04/05 (`git-core/`).
- Adjacent to: `git-worktrees/` — the worktree half of `JGitOperations` is left for PLAN-03, not copied here.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-02-git-core-import.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
