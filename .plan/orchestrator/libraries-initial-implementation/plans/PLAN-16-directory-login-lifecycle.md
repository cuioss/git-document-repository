# PLAN-16: git-directory — first login, periodic confirmation, erasure, prefilled entries

epic: libraries-initial-implementation
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Complete `git-directory` with the person life cycle: first login with a memory-only pseudonym, prefill and the
create-or-bind choice checked atomically on every save attempt (GDR-DIR-3), periodic confirmation (GDR-DIR-5), erasure
as one commit on `main` that deletes the entry and appends to `erasures.yaml` without rewriting history (GDR-DIR-6),
and prefilled unbound entries saved by an administrator and bound only by a first login (GDR-DIR-7).

## Deliverables

1. First login (GDR-DIR-3): entry required with prefill; binding candidates for entered memberships; create vs bind;
   save on `main` without review or release, effective immediately; author and `Actor` = the held or the bound
   pseudonym; per-attempt checks (subject hash unbound, chosen entry still unbound and matching) made atomic by the
   push with lease; not bindable.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
2. Prefilled entries (GDR-DIR-7): unbound entry (no subject hash, no `confirmed`, ≥ 1 membership) under a new
   pseudonym by an administrator, editable until bound, resolving like a bound entry, authoring no save before its
   binding commit.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Confirmation and erasure (GDR-DIR-5/6): `directory.confirmation-interval` listing and confirming; erasure commit
   with the erasure item (`pseudonym`, `date`, `memberships`), administrator's trailers, former-member resolution
   afterwards, a later login of the same issuer + `sub` getting a new unlinked pseudonym.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests: concurrent first logins of one identity, concurrent binds of one entry, interrupted first login leaving
   nothing in Git, erasure then re-login, GDR-NFR-3 log capture; specification state updated ("git-directory
   implemented").
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

## Done when

- `verify` passes at the plan's head with GDR-DIR-3/5/6/7 each covered by a test naming the requirement id; every
  GDR-DIR requirement is then covered.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the first-login atomicity rule ("every attempt of the save, the re-applications of GDR-STORE-3 included,
  must check against the tip") is specified in `doc/specification/git-directory.adoc` § GDR-DIR-3.
- HYPOTHESIS: `git-store`'s save lets the caller run a check against the tip on every re-application attempt —
  confirm/refute at the save implementation PLAN-08 lands in `git-store/` (its save operation / edit callback type)
  (verify-at-outline); refuted → report the gap via the inbox rather than editing `git-store`.

## Expected Surface

- HYPOTHESIS: `git-directory/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-04 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-directory.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-15.
- Overlaps with: PLAN-15 — strictly sequential.
- Adjacent to: application-side token handling — the caller supplies claims; the module never sees a token.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-16-directory-login-lifecycle.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
