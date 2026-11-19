# PLAN-12: git-forms — module, kinds as configuration, schema validation, YAML layout and codec

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Create `git-forms` (`de.cuioss.git.document.forms`, on `git-store`) and its data foundation: kinds of documents
defined by configuration only (GDR-FORMS-1), validation against plain JSON Schema with `required` deferred to the
transitions that check it, errors per JSON Pointer (GDR-FORMS-2), and the data layout with YAML written in schema key
order and stable formatting as the `git-store` codec (GDR-FORMS-3, GDR-STORE-11). The YAML library and the JSON Schema
validator are open dependencies the owner decides inside this plan.

## Deliverables

1. Dependency decisions: a YAML library and a JSON Schema validator (and the schema dialect, open in GDR-FORMS-2) put
   to the owner through the question tool with alternatives, native-image fitness (GDR-NFR-4) and footprint, BEFORE any
   code; the decision replaces "open" in `doc/specification/git-forms.adoc` and `general.adoc` § GDR-GEN-1.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
2. Module creation: pom, `module-info.java`, `LogRecord` holder, root `<modules>`, layering guard entry
   (`git-forms` → `git-store`), `AGENTS.md` / `module-structure.adoc` state; the `git-forms` outcome list
   (invalid, mandatory fields missing, mismatch, transition refused / step-up required).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
3. Kinds (GDR-FORMS-1): kind configuration (id, repository alias, collection, schema, template, workflow, optional
   numbering) loaded from a configuration repository or directory; adding a kind needs no code change.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Validation and layout (GDR-FORMS-2/3): validation before every save except `required`, errors as JSON Pointer +
   message preventing the save; `required` check exposed for the transitions of PLAN-13; per-document layout
   (`document.yaml`, `narrative/<name>.adoc`) and numbered layout (`<collection>/<number>.yaml`, `.counter`), the
   schema-declared split of `document.yaml`; the YAML codec with `encode(decode(encode(v))) == encode(v)` byte for
   byte.
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
- Documents hold no personal data beyond pseudonyms (`p-…`) and no encrypted field type (GDR-FORMS-2).
- The schema file stays plain JSON Schema usable unchanged by an outside validator; link checks (`gdr-link`) are
  PLAN-14.

## Done when

- `verify` passes at the plan's head with GDR-FORMS-1/2/3 each covered by a test naming the requirement id, and both
  dependency decisions recorded in the specification.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: GDR-FORMS-2 leaves the schema dialect open and `git-forms` lists its YAML library, JSON Schema validator
  and template engine as open — read at `doc/specification/git-forms.adoc` § intro and § GDR-FORMS-2.
- OBSERVED: `git-store` carries no YAML library; `git-forms` supplies the YAML codec — read at
  `doc/specification/git-store.adoc` § GDR-STORE-11.

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-forms/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-03 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `README.adoc` (module paragraph: `git-forms` is created)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-08 (save, codec use on the write path). PLAN-09..11 need not have landed.
- Overlaps with: PLAN-09/10/11 only on `pom.xml` / `general.adoc` / `AGENTS.md` — a multi-file overlap the gate
  puts to the operator if they run side by side; WS-03 plans strictly sequential among themselves.
- Adjacent to: `git-store/` — consumed, not changed; a missing store capability is reported in the PR, not absorbed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-12-forms-kinds-schema-layout.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
