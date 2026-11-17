# PLAN-17: git-timestamp — RFC 3161 anchor provider and token verification

epic: libraries-initial-implementation
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Create the optional module `git-timestamp` (`de.cuioss.git.document.timestamp`, on `git-store`) providing the
`AnchorProvider` `rfc3161`: obtain an RFC 3161 `TimeStampToken` for an object id from the configured authority
(GDR-TSA-1) and verify a stored token against the object id and `timestamp.trust` (GDR-TSA-2). The RFC 3161 client
library is an open dependency the owner decides inside this plan.

## Deliverables

1. RFC 3161 library decision put to the owner (alternatives, native-image fitness, size) before any code; the decision
   replaces "open" in `doc/specification/git-timestamp.adoc` and `general.adoc` § GDR-GEN-1.
   - Skills: pm-dev-java:java-security
   - Persona: security-expert
2. Module creation: pom, `module-info.java` `provides` `AnchorProvider` with id `rfc3161`, `LogRecord` holder, root
   `<modules>`, layering guard entry (`git-timestamp` → `git-store`), `AGENTS.md` / `module-structure.adoc` state.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
3. Provider (GDR-TSA-1): `TimeStampReq` with `messageImprint` = SHA-256 over the lowercase-hex object id,
   `certReq = true`, random nonce, previous proof unused; proof = DER `TimeStampToken`; unreachable → unavailable,
   error response → refused; `timestamp.url` (HTTPS), `timestamp.trust`, optional `timestamp.credentials` never
   stored or logged; no anchoring decisions, notes or pushes in the module.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Verification (GDR-TSA-2) and tests without network access: a local test TSA (in-process signer) for success,
   imprint mismatch, signature invalid, unreadable token, error response, unreachable; an end-to-end anchoring run
   through PLAN-11's engine with `anchor.provider = rfc3161`.
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-http-testing
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

## Done when

- `verify` passes at the plan's head with GDR-TSA-1/2 each covered by a test naming the requirement id, the library
  decision recorded, and no test reaching the network.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the request fields and outcomes are specified in `doc/specification/git-timestamp.adoc` § GDR-TSA-1 with
  RFC 3161 § 2.4 as source.
- OBSERVED: `cui-http-testing` (MockWebServer) is not in the dependency approval of 2026-11-17 — read at
  `.plan/orchestrator/libraries-initial-implementation/epic.md` § Decisions. Using it for the test TSA endpoint needs
  the owner's approval like any other dependency; an in-process JDK `HttpServer` needs none.

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-timestamp/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-05 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-timestamp.adoc`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`

## Dependencies and Sequencing

- Depends on: PLAN-11 (anchoring engine, `AnchorProvider` exercised end to end).
- Overlaps with: WS-03/WS-04 plans on `pom.xml`, `module-structure.adoc`, `AGENTS.md` (multi-file → operator
  question if run side by side).
- Adjacent to: `git-store/` — consumed, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-17-timestamp-rfc3161.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
