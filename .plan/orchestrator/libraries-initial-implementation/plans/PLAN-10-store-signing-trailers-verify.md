# PLAN-10: git-store — commit signature, identity trailers, signature verification

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Sign every commit the store writes in the SSH signature format through the active `SigningKeySource`, shipping the
source `file` (GDR-STORE-12); add the identity trailers `Actor`, `Actor-Issuer`, `Auth-Time`, `Auth-Context` to
every save and release commit (GDR-STORE-13); and verify the signatures of a reference against an `allowed_signers`
file with validity periods, runnable from CI without the application (GDR-STORE-14).

## Deliverables

1. Signature encoding decision: whether to use JGit's SSH signer of `org.eclipse.jgit.ssh.apache` (open in
   GDR-STORE-12, needs the owner's approval) or an own SSHSIG encoder — put to the owner with the trade-off before any
   code; record the decision in `doc/specification/git-store.adoc` and `general.adoc` § GDR-GEN-1.
   - Skills: pm-dev-java:java-security
   - Persona: security-expert
2. `Signer` implementation delegating to the active `SigningKeySource`, the `file` provider (`signing.key` = SSH
   private key path), passed to the in-core writing of `git-core` for every commit of save, release, checkpoint and
   notes.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
3. Identity trailers (GDR-STORE-13) on save and release commits only, from caller-supplied claims (`iss`,
   `auth_time`, `acr`, `amr`) and the pseudonym of the active `PersonDirectory`; never `sub` or `sid`; the store
   never receives the access token.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Verification (GDR-STORE-14): verify every commit of a reference against `signing.allowed-signers` (from
   configuration, never the repository) honouring `valid-after` / `valid-before` at commit time; list every commit
   without a valid signature; an entry point usable from CI. Tests incl. a stock `git verify-commit` cross-check when
   a Git ≥ 2.35 binary is present (skipped otherwise).
   - Skills: pm-dev-java:junit-core, pm-dev-java:java-security
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
- Key material is never written in clear text, logged or put into a detail (GDR-NFR-3); test keys are generated per
  test run.

## Done when

- `verify` passes at the plan's head with GDR-STORE-12/13/14 each covered by a test naming the requirement id, and
  the encoding decision recorded in the specification.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: GDR-STORE-12 states that whether the store uses `org.eclipse.jgit.ssh.apache` "is open and needs the
  owner's approval" — read at `doc/specification/git-store.adoc` § GDR-STORE-12.
- HYPOTHESIS: JGit 7.8.0 ships an SSH `Signer` (in `org.eclipse.jgit.ssh.apache`) that can sign with a key the
  caller supplies rather than one it reads from disk itself — confirm/refute at JGit `v7.8.0.202609011348-r`
  `org.eclipse.jgit.ssh.apache/src/org/eclipse/jgit/signing/ssh/SshSigner.java` § `sign` (verify-at-outline).

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/specification/general.adoc`

## Dependencies and Sequencing

- Depends on: PLAN-09 (release commits to carry trailers). Could technically follow PLAN-08 directly; kept after
  PLAN-09 because every WS-02 plan edits `git-store/`.
- Overlaps with: every WS-02 plan — strictly sequential.
- Adjacent to: the `signed` anchor key (PLAN-11), which reuses the key-source mechanism but must be a different key.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-10-store-signing-trailers-verify.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
