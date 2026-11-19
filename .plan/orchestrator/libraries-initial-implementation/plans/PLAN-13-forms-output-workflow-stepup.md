# PLAN-13: git-forms — generated output, workflow, step-up per transition

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Add the document life cycle to `git-forms`: generated AsciiDoc output from data and fragments, byte-identical for
equal input, committed with its data and regenerated on save, with a CI-runnable check (GDR-FORMS-4); workflows of
states and transitions mapped onto the store mechanisms `intermediate-save`, `save`, `release` (GDR-FORMS-5); and
step-up per transition from `Auth-Context` / `Auth-Time` (GDR-FORMS-7). The template engine is an open dependency the
owner decides inside this plan.

## Deliverables

1. Template engine decision put to the owner (alternatives, determinism, native-image fitness) before any code; the
   decision replaces "open" in `doc/specification/git-forms.adoc` and `general.adoc` § GDR-GEN-1.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
2. Generated output (GDR-FORMS-4): template joins data and fragments into one AsciiDoc file; regenerated from the data
   of the commit a save creates (after re-applying onto the remote tip), never merged; check operation runnable from
   CI; kinds without a template generate and check nothing.
   - Skills: pm-dev-java:java-core, pm-documents:ref-asciidoc
   - Persona: implementer
3. Workflow (GDR-FORMS-5): workflow file (states, transitions with mechanism, deadlines section parsed for PLAN-14), the
   default workflow of the spec, transition refused for a disallowed transition, the `state` field written by the
   transition, `required` checked on leaving the first state and on every `release` transition (mandatory fields
   missing with the JSON Pointers).
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Step-up (GDR-FORMS-7): `step-up` per transition with the defaults (release requires, others do not),
   `intermediate-save` with step-up refused at load, the check against `step-up.amr` (default `mfa`),
   `step-up.acr` and `step-up.max-age`; tests for every row including the deviating example of the spec.
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

## Done when

- `verify` passes at the plan's head with GDR-FORMS-4/5/7 each covered by a test naming the requirement id, and the
  template-engine decision recorded in the specification.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the default workflow, the step-up keys and their defaults are specified in
  `doc/specification/git-forms.adoc` § GDR-FORMS-5 and § GDR-FORMS-7.
- HYPOTHESIS: `git-store`'s save offers a hook to regenerate derived files from the re-applied commit's data on every
  attempt — confirm/refute at the save implementation PLAN-08 lands in `git-store/` (its save operation type)
  (verify-at-outline); refuted → report the gap to the orchestrator via the inbox instead of editing `git-store`.

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-forms/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-03 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/specification/general.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-forms`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-12; PLAN-09 (release) and PLAN-10 (trailers carrying `Auth-Context` / `Auth-Time`).
- Overlaps with: WS-03 plans — strictly sequential.
- Adjacent to: deadlines (GDR-FORMS-6) — parsed here, evaluated in PLAN-14.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-13-forms-output-workflow-stepup.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
