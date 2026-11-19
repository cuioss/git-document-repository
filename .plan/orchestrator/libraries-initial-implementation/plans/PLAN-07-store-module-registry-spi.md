# PLAN-07: git-store — module, repository registry, configuration, codecs and service interfaces

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Create the module `git-store` (`de.cuioss.git.document.store`, depending on `git-core` only) and lay its
foundation: the repository registry addressed by alias (GDR-STORE-1), the store configuration of
`doc/specification/git-store.adoc` § Configuration, the codec interface with byte-identical round-trips
(GDR-STORE-11), the closed outcome list of `git-store` (GDR-GEN-3), and the three service interfaces
`SigningKeySource`, `AnchorProvider` and `PersonDirectory` with `ServiceLoader` provider selection by
configuration (exactly one active signing source and person directory, at most one anchor provider; a missing or
duplicate provider id stops the start).

## Deliverables

1. Module creation: `git-store/pom.xml`, `module-info.java` with `uses` for the three interfaces, `@NullMarked`
   package, `LogRecord` holder and catalogue; root `pom.xml` `<modules>`, the PLAN-06 layering guard entry
   (`git-store` → `git-core`), `AGENTS.md` module table and `doc/concepts/module-structure.adoc` state.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Registry and configuration (GDR-STORE-1): alias `[a-z0-9-]+`, remote (HTTPS URL or local bare path, checked
   against the remote policy), confidentiality class, optional retention (reported, never acted on); a typed
   configuration of every key in § Configuration with required keys and defaults validated at start. The source format
   of the configuration (the spec shows YAML) must not pull a YAML library into `git-store` — accept an
   already-parsed structure and leave parsing to the application or `git-forms`.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Codec interface (GDR-STORE-11) and the `git-store` outcome list as its own result shape next to `GitResult`
   (incomplete, unavailable, refused, no proof, imprint mismatch, signature invalid, chain broken, not supported /
   managed by source).
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
4. Service interfaces and provider selection: `SigningKeySource` (signs bytes with a key the source holds; provider
   `file` reading an SSH private key path follows in PLAN-10), `AnchorProvider` (obtain with previous proof, verify
   with established time — GDR-STORE-15 contract only), `PersonDirectory` (the full operation set of GDR-STORE-19 as an
   interface only); `ServiceLoader` lookup by provider id with the start refusals; tests with test providers.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer

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

## Done when

- `verify` passes at the plan's head with `git-store` in the reactor, GDR-STORE-1 and GDR-STORE-11 tested, and the
  provider-selection refusals (missing id, duplicate id) tested.
- The layering guard rejects a `git-store` dependency on any reactor module other than `git-core`.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `git-store` does not exist at HEAD; the reactor has three modules — read at `pom.xml` § `<modules>`.
- OBSERVED: the provider-selection rules (exactly one / at most one, refusal on missing or duplicate id) are stated in
  `doc/specification/git-store.adoc` § intro.
- HYPOTHESIS: the configuration can be modelled without a third-party dependency in `git-store` (parsing stays with
  the caller) — confirm/refute against `doc/specification/git-store.adoc` § Configuration and
  `doc/concepts/storage-engine.adoc` at outline (verify-at-outline); refuted → the owner decides a library.

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `README.adoc` (module paragraph: `git-store` is created)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-06 (layering guard to extend; foundation complete).
- Overlaps with: every WS-02 plan (`git-store/`, `git-store.adoc`) — strictly sequential.
- Adjacent to: `git-forms` (YAML codec, PLAN-12), `git-directory` (`git` person directory, PLAN-15),
  `git-timestamp` (`rfc3161`, PLAN-17) — interfaces only here.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-07-store-module-registry-spi.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
