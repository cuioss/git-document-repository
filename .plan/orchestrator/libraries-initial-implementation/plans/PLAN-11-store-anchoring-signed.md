# PLAN-11: git-store — anchoring: subjects, checkpoints, proofs as notes, own timestamp signed

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Complete `git-store` with the anchoring: which subjects are anchored and when (release commits, checkpoints with
their `refs` file and never-moved `refs/tags/checkpoints/<n>` tags — GDR-STORE-16), proofs stored as base64 notes
under `refs/notes/anchors/<provider>`, pushed with lease, pending subjects derived from Git state and retried, and
`verifyAnchors()` from CI (GDR-STORE-15, GDR-STORE-17), and the own timestamp provider `signed` with its signed
statement chain (GDR-STORE-18).

## Deliverables

1. Anchoring engine (GDR-STORE-15/16): at most one active provider by `anchor.provider`; release-tag commits anchored
   unless `anchor.releases` is false; checkpoint commit (tree `refs`, parent the previous checkpoint, technical user,
   `Checkpoint <n>`) when `anchor.checkpoint-interval` passed and a listed reference changed (checkpoint tags and
   anchor notes never make one due); due check after every push/fetch and every `anchor.retry-interval`; a failing
   provider never blocks a save, release or fetch.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Proofs as notes (GDR-STORE-17): base64 note on the anchored commit, pushed with lease right after writing,
   separately from save commits; pending = subjects without a remote note as of the last fetch; a local-only note is
   pushed, not re-requested; `previous` = newest anchored proof.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Provider `signed` (GDR-STORE-18): the statement format `anchor signed 1` / object / time / sequence / previous,
   armored SSH signature with namespace `git-document-anchor`, anchor key from `anchor.signed.key-source` /
   `anchor.signed.key` (start fails if equal to the commit-signing key), clock checks against the previous proof and
   `anchor.signed.max-clock-skew`, INFO log record per proof (alias, object id, time, sequence, SHA-256 of the proof).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Verification: `verifyAnchors()` walks the checkpoint chain and release tags, checks every proof, lists unreachable
   or retargeted references a checkpoint lists and missing checkpoint numbers; `signed` chain checks (no gaps,
   predecessor hash, non-decreasing time) against `anchor.signed.allowed-signers`. Tests incl. an `ssh-keygen -Y
   verify` cross-check when OpenSSH is present (skipped otherwise).
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
- The provider only obtains and verifies proofs; every decision about subjects, notes and pushes stays in the engine,
  so `rfc3161` (PLAN-17) plugs in without engine changes.

## Done when

- `verify` passes at the plan's head with GDR-STORE-15/16/17/18 each covered by a test naming the requirement id;
  every GDR-STORE requirement is then covered.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the checkpoint tree format, the statement format and the namespace `git-document-anchor` are specified
  in `doc/specification/git-store.adoc` § GDR-STORE-16 and § GDR-STORE-18.
- HYPOTHESIS: the SSHSIG armoring chosen in PLAN-10 can sign arbitrary bytes with a custom namespace (not only commit
  payloads) — confirm/refute at the implementation PLAN-10 lands (its `Signer` / SSHSIG encoder type in
  `git-store/`) (verify-at-outline).

## Expected Surface

- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-store`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-09 (release tags), PLAN-10 (SSH signature format, key source).
- Overlaps with: every WS-02 plan — strictly sequential; PLAN-17 implements the same `AnchorProvider` interface.
- Adjacent to: `git-timestamp` — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-11-store-anchoring-signed.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
