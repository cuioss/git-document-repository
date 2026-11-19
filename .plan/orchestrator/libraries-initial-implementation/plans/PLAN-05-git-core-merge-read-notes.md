# PLAN-05: git-core — in-core merge, reading and git notes

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Complete `git-core` with the remaining store primitives: the path-level in-core merge with its own conflict-path
computation (GDR-CORE-6), reading snapshots, path history and changed paths between commits (GDR-CORE-9), and git
notes under a caller-named notes reference written through compare-and-swap (GDR-CORE-10).

## Deliverables

1. In-core merge (GDR-CORE-6): `MergeStrategy.SIMPLE_TWO_WAY_IN_CORE.newMerger(repo)`; success yields a tree id; on
   failure the conflicting paths are computed by a tree walk over base and both tips (entry — object id or file mode —
   differing from the base on both sides and between the sides); no commit on conflict. `MergeStrategy.RECURSIVE` is
   never used (ADR-0001).
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
2. Reading (GDR-CORE-9): snapshot of the tree of a reference, tag or commit; commits that changed a path, newest first,
   with author, committer, time and message; added / modified / deleted paths between two commits.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Notes (GDR-CORE-10): read and write a note on a commit under a caller-named notes reference through `NoteMap`
   (`read`, `set`, `writeTree`), the notes-reference update through the compare-and-swap of PLAN-04.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests: merge without and with conflicts (content and file-mode conflicts, one-sided changes, deletions), reading on
   the in-memory fixture, notes concurrency (two writers on one notes reference); `doc/specification/git-core.adoc`
   and `AGENTS.md` state updated ("git-core implemented").
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing, pm-documents:ref-asciidoc
   - Persona: module-tester
5. Test and verification residuals of PLAN-02 (leftovers of its landing analysis, folded here because this plan
   closes `git-core`; each item is tested, or recorded as an accepted exception with its reason in
   `doc/specification/git-core.adoc`): (a) a test that ties the constants of `GitOutcome` to the GDR-GEN-3 table in
   `doc/specification/general.adoc` (the 20 rows are held by reading only); (b) the tautological assertions of
   `AbsenceContractsTest` (`assertNull` on a value just passed to a constructor) replaced by assertions that can fail;
   (c) refusal of a hook on the REMOTE side (`ReceivePack`) — first confirm in the JGit 7.8.0 sources whether
   `ReceivePack` runs native hooks at all, then test it or record the answer; (d) an end-to-end redirect test through
   `fetch` or `fetchCommit` (the guard is tested only at transport level) — a local HTTPS server needs a certificate
   that cannot be made without a new dependency or an external tool, so the choice goes to the owner through the
   question tool BEFORE any is added; (e) the declined CodeRabbit finding about `http.extraHeader` / `http.cookieFile`
   from user or system configuration reaching the remote: verify in the JGit 7.8.0 sources whether JGit applies them and
   record the answer; (f) untested branches named by the analysis: `publish()` losing a race, the
   `requireMirroredRefs` failure branch and the log path of a refused redirect.
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
- Rules the review of PLAN-04 (PR #35) established for the code this plan adds beside it in `git-core` (each was a
  defect found in review and is now fixed and tested in the merged `InCoreWriter` / `ReferenceUpdater`; verify the
  JGit behaviour at 7.8.0 before relying on it): (1) map a caught exception to a result only through
  `JGitRepositoryOperations.failure(e)`, never a hand-written `getClass().getSimpleName()` detail (an anonymous
  exception class has an empty simple name and `GitResult.of` rejects a blank detail); (2) where a contract says "the
  id must name a commit" resolve it with `RevWalk.parseAny` and check the type, because `parseCommit` peels an annotated
  tag; (3) a `RefUpdate` that acts on the named reference itself (the notes-reference update, a delete) uses the
  detaching overload `updateRef(name, true)`, and its test includes a symbolic-reference case; (4) a concurrency test
  that runs on both fixtures (deliverable 4, two writers on one notes reference) asserts the safety invariant on the
  local fixture and a liveness bound only on the in-memory one, because the lock-file reference database reports
  `LOCK_FAILURE` while another thread merely holds the lock.

## Done when

- `verify` passes at the plan's head with GDR-CORE-6, GDR-CORE-9 and GDR-CORE-10 each covered by a test naming the
  requirement id; every GDR-CORE requirement is then covered.
- Every item of deliverable 5 is tested at the plan's head, or recorded as an accepted exception with its reason in
  `doc/specification/git-core.adoc`; a new test dependency is in the POM only with the owner's recorded approval.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `pm-provider-git` has no merge, no `NoteMap` and no diff between commits — grep for `Merger`,
  `MergeStrategy`, `NoteMap`, `DiffEntry` in
  `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/main/java/de/planmarshall/provider/git/` at pm-mcp-core
  `287e1a86` finds none.
- OBSERVED: the spec states `StrategySimpleTwoWayInCore` reports a path modified by both sides as a conflict and
  reports no conflicting paths, with its JGit 7.8.0 source link — `doc/specification/git-core.adoc` § GDR-CORE-6.
- HYPOTHESIS: `NoteMap.read`, `NoteMap.set` and `NoteMap.writeTree` have the needed signatures in JGit 7.8.0 —
  confirm/refute at JGit `v7.8.0.202609011348-r` `org.eclipse.jgit/src/org/eclipse/jgit/notes/NoteMap.java` §
  `NoteMap` (verify-at-outline).
- OBSERVED: the residuals of deliverable 5 were read at PLAN-02's merge commit `2c728bc`: the redirect tests call the
  guard and the connection factory directly
  (`git-core/src/test/java/de/cuioss/git/document/core/JGitRepositoryOperationsTest.java` lines 329-371); only a local
  `pre-push` hook is tested (same file, lines 953-981); `AbsenceContractsTest` asserts absence on values it just
  passed in; `GitOutcome` has 20 constants and the GDR-GEN-3 table 20 rows, with no test between them.
- HYPOTHESIS: JGit 7.8.0 `ReceivePack` does not run native hooks, so a remote-side hook refusal cannot occur — confirm/
  refute at JGit `v7.8.0.202609011348-r` `org.eclipse.jgit/src/org/eclipse/jgit/transport/ReceivePack.java` (verify-at-outline).
- Verify-first clause: the three-layer analysis of PLAN-02 read the code only (no build, no test run); every OBSERVED
  claim of deliverable 5 is re-read at the plan's own HEAD at outline, and a refuted one is dropped from the item that
  names it.

## Expected Surface

- OBSERVED: `git-core/src/main/java/de/cuioss/git/document/core/`
- HYPOTHESIS: `git-core/src/test/java/` (created by PLAN-02)
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)
- HYPOTHESIS: `git-core/pom.xml` (a test-scope dependency for the HTTPS test server of deliverable 5 d, only with the
  owner's approval)

## Dependencies and Sequencing

- Depends on: PLAN-04 (compare-and-swap for the notes reference).
- Overlaps with: PLAN-04 (same package) — sequential.
- Adjacent to: nothing in `git-store/` yet — the store's use of merge (save, release) is PLAN-08/09.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-05-git-core-merge-read-notes.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
