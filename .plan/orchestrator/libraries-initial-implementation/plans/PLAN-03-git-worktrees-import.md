# PLAN-03: git-worktrees — linked working trees, commit from a working tree, working-tree status

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fill `git-worktrees` on `git-core`: take over `LinkedWorktrees` and the worktree half of `GitOperations` /
`JGitOperations` from `pm-provider-git` (plain copy, provenance in the commit message) as `WorktreeOperations` —
add, list and remove linked working trees in git's own administration layout (GDR-WT-1) and commit every change of a
working tree in one operation (GDR-WT-2) — and add the new working-tree status of three path sets (GDR-WT-3), on which
`pm-mcp-core` will later build its `git.worktree-sha` digest.

## Deliverables

1. Module wiring: `git-worktrees/pom.xml` depends on `git-core`, `module-info.java` `requires
   de.cuioss.git.document.core`, `LogRecord` holder and catalogue. `LinkedWorktrees` moved with `LinkedWorktreesTest`.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. `WorktreeOperations`: `worktreeAdd` / `worktreeList` / `worktreeRemove` (administration files `gitdir`, `commondir`,
   `HEAD`, the `.git` file — gitrepository-layout(5), git-worktree(1)) and the commit from a working tree with
   caller-supplied author, committer and message, hooks refused through `RefusingFS` (GDR-WT-1, GDR-WT-2). Outcomes
   come from `GitOutcome` as settled in PLAN-02.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
3. Working-tree status (GDR-WT-3): `changed`, `untracked`, `deleted` path sets and nothing else; no digest in this
   module.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests: the worktree part of `JGitOperationsTest`, `GitOperationsContractTest`, `GitOperationsRecordsTest` and
   `AbsenceContractsTest` (the split PLAN-02 recorded), status tests, and the `git worktree list` compatibility test,
   run only when a Git binary is present (skipped otherwise, never failing). `doc/specification/git-worktrees.adoc`,
   `doc/clients/pm-mcp-core.adoc` (worktree side of open question 1) and `AGENTS.md` updated.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing, pm-documents:ref-asciidoc
   - Persona: module-tester

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
- `WorktreeShaDigest`, its `ported/*` tests and fixtures stay in `pm-mcp-core` (their framing is a PM-MCP contract)
  and are NOT copied.
- A test that starts a `git` process does so from the test only; the library itself never starts a process
  (GDR-CORE-1).

## Done when

- `verify` passes at the plan's head with GDR-WT-1/2/3 each covered by a test naming the requirement id.
- `git-worktrees` depends on `git-core` and on no other reactor module.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `LinkedWorktrees` (172 lines, package-private) and `LinkedWorktreesTest` (85 lines) exist in
  `pm-provider-git` — read at `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/main/java/de/planmarshall/provider/git/LinkedWorktrees.java`
  at pm-mcp-core `287e1a86`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at pm-mcp-core 287e1a86: LinkedWorktrees.java 172 lines, LinkedWorktreesTest.java 85 lines (PR #30 analysis)
- OBSERVED: the worktree operations `worktreeAdd`, `worktreeList`, `worktreeRemove`, `commit(CommitInput)` and
  `worktreeSha` are declared on `GitOperations` — read at `GitOperations.java` § `GitOperations`.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at pm-mcp-core 287e1a86: GitOperations.java declares worktreeAdd (54), worktreeList (62), worktreeRemove (72), commit (82) and worktreeSha (44)
- HYPOTHESIS: no three-set status exists in the source; `worktreeSha` computes the digest directly from the working
  tree — confirm/refute at `JGitOperations.java` § `worktreeSha` and `WorktreeShaDigest.java` § `WorktreeShaDigest`
  (verify-at-outline). If a reusable status computation exists, move it rather than rewrite it.
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at pm-mcp-core 287e1a86: JGitOperations.worktreeSha builds the digest inline from the JGit Status (lines 134-141); no reusable three-set status exists, so GDR-WT-3 was written new (PR #30)
- OBSERVED: JGit 7.8.0 has no command that adds a linked working tree — stated with its source in
  `doc/specification/git-worktrees.adoc` § intro (JGit `api` package link).
  - verdict: corroborated | checked_at: e89cf862f5d518a5b9bec6e064ed317e06e8d458 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: no class with 'worktree' in its name under org/eclipse/jgit/api in the JGit 7.8.0 sources jar (read by the orchestrator and the leaf)

## Expected Surface

- OBSERVED: `git-worktrees/pom.xml`
- OBSERVED: `git-worktrees/src/main/java/module-info.java`
- OBSERVED: `git-worktrees/src/main/java/de/cuioss/git/document/worktrees/`
- HYPOTHESIS: `git-worktrees/src/test/java/` (new test tree)
- OBSERVED: `doc/specification/git-worktrees.adoc`
- OBSERVED: `doc/clients/pm-mcp-core.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/concepts/module-structure.adoc` (State column of `git-worktrees`)
- HYPOTHESIS: `README.adoc` (module paragraph: `git-worktrees` leaves the scaffold state)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-02 (`RefusingFS`, `GitResult`, `GitOutcome`, the recorded test split).
- Overlaps with: PLAN-02 (`doc/clients/pm-mcp-core.adoc`, `AGENTS.md`). May run beside PLAN-04 (disjoint apart from
  `AGENTS.md`).
- Adjacent to: `git-core/` — consumed, not changed; a missing outcome is added in PLAN-02's style only if PLAN-02 did
  not already settle it, and then named in the PR.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-03-git-worktrees-import.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
