# PLAN-04: git-core — in-core writing and compare-and-swap reference updates

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Add the two write primitives the document store stands on (ADR-0001): creating blobs, trees and commits from
in-memory content without a working tree, optionally signed through a caller-supplied JGit `Signer` (GDR-CORE-4), and
the compare-and-swap reference update with re-application of the caller's operation, a caller-set number of attempts,
and the compare-and-delete that never adopts a moved reference (GDR-CORE-5). Both are new code; `pm-provider-git` has
neither.

## Deliverables

1. In-core writing (GDR-CORE-4): edits (set a path to content, delete a path) applied to the parent's tree in an
   in-core `DirCache` (`DirCache.read(ObjectReader, AnyObjectId)`, `DirCache.writeTree(ObjectInserter)`), blob, tree
   and commit inserted through one `ObjectInserter`; author, committer, message and parents from the caller; an
   optional `org.eclipse.jgit.lib.Signer`; nothing written outside the object database and references (asserted by a
   test that snapshots the repository directory).
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
2. Compare-and-swap update (GDR-CORE-5): the caller supplies an operation (current tree → edits); every update sets the
   expected old id (`ObjectId.zeroId()` for a must-not-exist reference), never the force flag; `NEW` / `FAST_FORWARD`
   success; `LOCK_FAILURE` re-applies and retries up to the attempts passed; after the last attempt conflict with the
   reference unchanged; `IO_FAILURE` an exception; every other result rejected with the result named in the detail.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
3. Compare-and-delete (GDR-CORE-5): expected old id plus force flag, `FORCED` success; after `LOCK_FAILURE` re-read —
   unchanged → retry with the same expected id, moved → conflict at once, reference unchanged.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests on the in-memory and local fixtures, including concurrent updaters (threads) for retry and conflict, a delete
   racing a newer draft commit, and a signed commit verified with a test `Signer`; `doc/specification/git-core.adoc`
   state updated.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
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
- `git-core` signs nothing itself and reserves no reference name; push refspecs and notes references stay caller
  configuration (`doc/concepts/module-structure.adoc` § Dependencies and Third-Party Isolation).

## Done when

- `verify` passes at the plan's head with GDR-CORE-4 and GDR-CORE-5 (update and delete branches) each covered by a
  test naming the requirement id.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `pm-provider-git` has no `ObjectInserter`-based write, no `DirCache.read(ObjectReader, …)` use and no
  retrying reference update; its only `setExpectedOldObjectId` call creates a reference with `ObjectId.zeroId()` —
  grep of `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/main/java/de/planmarshall/provider/git/JGitOperations.java`
  at pm-mcp-core `287e1a86` (line ~270).
- OBSERVED: the `RefUpdate` semantics (expected-old check before the force flag, `LOCK_FAILURE` on a moved reference,
  `REJECTED` for a delete without force) are stated with their JGit 7.8.0 source link in
  `doc/specification/git-core.adoc` § GDR-CORE-5.
- HYPOTHESIS: `DirCache.read(ObjectReader, AnyObjectId)` and `DirCache.writeTree(ObjectInserter)` exist with these
  signatures in JGit 7.8.0 — confirm/refute at JGit `v7.8.0.202609011348-r`
  `org.eclipse.jgit/src/org/eclipse/jgit/dircache/DirCache.java` § `read` / `writeTree` (verify-at-outline).
- HYPOTHESIS: the `Signer` hook is reachable from a commit built by `ObjectInserter` (not only through `CommitCommand`)
  — confirm/refute at JGit 7.8.0 `org.eclipse.jgit/src/org/eclipse/jgit/lib/Signer.java` § `sign` and
  `org.eclipse.jgit/src/org/eclipse/jgit/lib/CommitBuilder.java` § `setGpgSignature` (verify-at-outline).

## Expected Surface

- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- HYPOTHESIS: `git-core/src/test/java/` (created by PLAN-02)
- OBSERVED: `doc/specification/git-core.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-02 (`RepositoryOperations`, `GitResult`, `GitOutcome`).
- Overlaps with: PLAN-05 (same package and `git-core.adoc`) — PLAN-05 runs after this plan.
- Adjacent to: `git-worktrees/` (PLAN-03) — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-04-git-core-incore-write-cas.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
