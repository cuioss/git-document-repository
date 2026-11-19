# PLAN-09: git-store — release, in-memory index, post-commit listeners

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Implement the read and publish side of the store: release by merging a working branch into `main` with a never-moved
tag `refs/tags/<collection>/<id>/v<n>` and the incomplete/repair path (GDR-STORE-6), the in-memory index fed by
consumer-registered contributors with periodic fetch and reporting-date snapshots (GDR-STORE-9) meeting the footprint
limit GDR-NFR-1, and in-process post-commit listeners (GDR-STORE-10).

## Deliverables

1. Release (GDR-STORE-6): merge working branch into `main` (path-level, entry path only for a numbered collection),
   tag `v<n>` counting from 1, `main` with lease and the tag pushed together, incomplete when `main` moved without
   the tag and a repeated release creating the missing tag instead of merging again, existing tags never moved or
   deleted, rejected for a refused push of `main`.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Index (GDR-STORE-9): index interface and per-collection contributors; built from `main` and every working and
   collection branch at start, after every fetch, save and release (only changed documents re-read after a save);
   periodic fetch at `fetch.interval`; `snapshotIndex(tag | commit)` without changing the current index.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
3. Listeners (GDR-STORE-10): subscriptions per branch, called oldest first after pushed saves/releases and after
   fetches that move a subscribed branch; a failing listener never fails the trigger; no listener progress kept.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests incl. GDR-NFR-1: 500 generated documents × 90 fields in an in-memory repository, index retained heap < 16 MB.
   The measuring tool is open in `doc/specification/general.adoc`; a tool that adds a dependency is put to the owner
   first, and the decision replaces "is open".
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
- The release commit carries the releasing user's identity trailers once PLAN-10 lands; this plan leaves the hook
  point for them and does not invent a trailer format.

## Done when

- `verify` passes at the plan's head with GDR-STORE-6/9/10 and GDR-NFR-1 each covered by a test naming the
  requirement id.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the release, index and listener contracts are specified in `doc/specification/git-store.adoc` §
  GDR-STORE-6, GDR-STORE-9, GDR-STORE-10; the index footprint limit in `doc/specification/general.adoc` § GDR-NFR-1.
- HYPOTHESIS: the retained size of the index object graph cannot be measured reliably with the approved dependencies
  alone (a heap-walk tool such as JOL would be a new dependency) — confirm/refute at
  `doc/specification/general.adoc` § GDR-NFR-1 ("the measuring tool is open") and the approved list in the epic's
  Decisions at outline (verify-at-outline); confirmed → owner decision on a tool before the test is written.

## Expected Surface

- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/specification/general.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-store`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-08 (save, working branches, fetch).
- Overlaps with: every WS-02 plan — strictly sequential.
- Adjacent to: anchoring of release tags (GDR-STORE-16) — PLAN-11.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-09-store-release-index-listeners.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
