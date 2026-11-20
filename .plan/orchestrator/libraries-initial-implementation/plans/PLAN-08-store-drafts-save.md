# PLAN-08: git-store — drafts, explicit save, conflicts, concurrent change detection, expiry

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Implement the write path of the document store: per-user drafts on never-pushed references (GDR-STORE-2), the
explicit save as a path-level squash onto the remote tip pushed with lease and re-applied up to
`save.max-attempts` (GDR-STORE-3), conflict handling per path (GDR-STORE-4), concurrent change detection by
`expectedBlobId` (GDR-STORE-5), draft expiry with garbage collection (GDR-STORE-7), and author, committer and
credential rules (GDR-STORE-8), with the concurrent-writers requirement GDR-NFR-2 as its acceptance test.

## Deliverables

1. Reference layout and drafts (GDR-STORE-2): the reference table of the spec, per-document vs numbered collections
   (never both; names `main` / `checkpoints` refused), ULID document ids, intermediate save as a commit on
   `refs/drafts/<pseudonym>/<collection>/<id>`, the push refspec carrying heads, tags and notes and never drafts,
   listing a user's drafts.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
2. Explicit save (GDR-STORE-3/4/5): the four steps of the save sequence (fetch → apply as one commit with the tip as
   only parent → push with lease → move local branch, compare-and-delete the draft), `expectedBlobId` per edit,
   conflict naming every path with nothing pushed and draft/branch unchanged, unreachable changing nothing, a missing
   target branch starting at `main`, saving onto `main` for content without release; results saved / conflict /
   rejected / unreachable / credentials expired or invalid. The value of `save.max-attempts` that this save consumes is
   bounded first (a leftover of PLAN-07's landing analysis, `landings/PLAN-07.md` L3-1): `StoreConfiguration.readSave`
   refuses only a value below 1, so `Integer.MAX_VALUE` is accepted and, handed to `git-core`'s
   `ReferenceUpdater.swap` (`for (int attempt = 1; attempt <= attempts; attempt++)`), `attempt++` wraps and the loop never
   ends when every try loses its race. Add an upper bound to the configuration, state it in `git-store.adoc`
   § Configuration, refuse a value above it as `MALFORMED` naming `save.max-attempts` (never the value), and test the
   bound, the value just above it and `Integer.MAX_VALUE`; the loop condition itself in `git-core` is PLAN-20's
   deliverable 3, so this plan relies on the bound and does not edit `git-core`.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
3. Author, committer, credentials (GDR-STORE-8): pseudonym as author with empty e-mail, technical user as committer,
   only the repository's technical-user credential for fetch and push; store-written commits never combined with a
   save's commits.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Draft expiry (GDR-STORE-7): drafts unchanged for `drafts.expiry` deleted by compare-and-delete; garbage collection
   of objects reachable only from deleted drafts.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
5. Tests: save happy path, lease refusal and re-apply, conflict, `expectedBlobId` conflict vs overwrite, unreachable
   remote, newer draft kept, expiry; GDR-NFR-2 — ten threaded writers on different files of one branch all succeed
   with retries only (attempts ≥ 10), two writers on one file with loaded blob ids yield exactly one saved and one
   conflict — against a `git-testing` local remote.
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
- No draft commit may become reachable from a pushed reference; the local working branch moves only after the remote
  accepted the commit.
- Five deliverables: tightly coupled (one save algorithm); proceeding unsplit is recorded as an epic decision.

## Done when

- `verify` passes at the plan's head with GDR-STORE-2/3/4/5/7/8 and GDR-NFR-2 each covered by a test naming the
  requirement id.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the save sequence, the reference table and the ULID rule are specified in
  `doc/specification/git-store.adoc` § GDR-STORE-2 / GDR-STORE-3 and `doc/resources/diagrams/save-sequence.svg`.
- HYPOTHESIS: a ULID generator is needed and no approved dependency provides one, so it is written in `git-store` (26
  Crockford base32 characters, 48-bit time + 80 random bits) — confirm/refute at the approved dependency set
  (`cui-java-tools` jar § any ULID / base32 type) at outline (verify-at-outline).
- HYPOTHESIS: garbage collection of unreachable draft objects works in process with JGit's `GC` on a
  `FileRepository` without starting a process — confirm/refute at JGit 7.8.0
  `org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/GC.java` § `gc` (verify-at-outline).
- OBSERVED: `save.max-attempts` has no upper bound in `StoreConfiguration.readSave` (`git-store/src/main/java/de/cuioss/git/document/store/StoreConfiguration.java`
  171-179: only `attempts < 1` is refused) and `ReferenceUpdater.swap` loops `attempt <= attempts` over an `int`
  (`git-core/src/main/java/de/cuioss/git/document/core/ReferenceUpdater.java` 199) — read at `a558afd`.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: StoreConfiguration.java 171-179 refuses only attempts < 1; ReferenceUpdater.java 199 loops attempt <= attempts over an int; both re-read at a558afd

## Expected Surface

- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/specification/general.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-store`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-07 (module, registry, configuration, `PersonDirectory` interface for the author pseudonym; shipped, PR #41).
- Runs after PLAN-21 (merge of `git-worktrees` into `git-core`; the operator's choice on 2026-11-20): the two share `AGENTS.md`,
  `doc/README.adoc` and `doc/specification/general.adoc`, and the merge rewrites the module table and the state lists this plan
  extends. Emit it when PLAN-21 has landed.
- Overlaps with: every WS-02 plan — strictly sequential.
- Adjacent to: GDR-STORE-12/13 (signature, trailers) — commits are written unsigned and without trailers here; PLAN-10
  adds both on the same commit path.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-08-store-drafts-save.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
