# PLAN-27: git-forms — stages within a document

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Give `git-forms` the concept of stages within a document, so that a kind whose process is grouped as tabs (triage, an assessment that
applies only under a condition, response, notifications, review) gets conditional mandatory fields, completion gated by predecessors and a
release that skips the stages that do not apply. The request is the consumer request `audit-notary-002` of Audit Notary (cuioss/Audit-Notary,
the concept `doc/concepts/staged-documents.adoc`, requested by the owner of that project on 2026-11-24), staged on the operator's word of
2026-11-25. **Held back from `next` until the operator releases it.** Whether the library takes the full concept or only the minimal
alternative (the `required` check at the first-state exit and at every release accepts a set of top-level parts to exclude) is put to the
operator at the outline: it changes GDR-FORMS-2 and GDR-FORMS-5 and the concept.

## Deliverables

1. The decision, put to the owner before any code (alternatives: the full stages concept; the minimal excluded-parts set; stages left to the
   consumer), recorded in `doc/concepts/schema-driven-documents.adoc`, `doc/specification/git-forms.adoc` § GDR-FORMS-2 and -5 and, if an
   outcome is added, `general.adoc`'s outcome table. A new `FormsOutcome` constant needs the owner's approval; reusing
   `MANDATORY_FIELDS_MISSING` for an open stage is the alternative to decide.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
2. Conditions on fields (the open question of the concept's § Open Questions, the second item of the list at `3e3cfc4`, which the consumer
   cites as 3): the condition language the deadline rules of GDR-FORMS-6 need (a condition on a field), extended by `any` and `all`, written
   once and used by both the deadline rules and the stage condition `when`; evaluated on the data model, bounded, a result and never an exception
   for a hostile condition.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
3. Stages in the workflow (GDR-FORMS-5): `stages` in `workflow.yaml`, validated when the workflow is loaded (the part is a top-level schema
   property of `"type": "object"` with `"gdr-split": true`, no part belongs to two stages, `after` names known stages, no cycles, `when` parses,
   `narrative` names known fragments, `complete.step-up` defaults to `false`); the stage status derived from the data and available in the index
   (`not-applicable`, `open`, `completed` where `completed` is stored in the part as `completion: {at, by}` with `by` a pseudonym).
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. The operations `complete` and `reopen`, each with the mechanism `save` and optional step-up (GDR-FORMS-7): completion is refused while a
   predecessor stage is open (a predecessor that does not apply counts as done), and while a mandatory field of the stage's part is missing (the JSON Pointers start with the
   part); a completed part is read-only until reopened; reopening removes `completion` in a save; editing is never gated.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
5. The gate (GDR-FORMS-2, GDR-FORMS-5): the transition out of the first state and every release are refused with an outcome that names the open
   stages, and they check `required` only for the document part and the applicable stages (the `required` fields of a stage that does not apply are
   not checked, which gives the conditional mandatory fields the schema cannot express, because `required` under `if`, `then` and `else` stays
   refused by GDR-FORMS-2); tests for the five cases of the request: a stage that does not apply with mandatory fields missing does not block a
   release; an applicable stage still open blocks submission; completion is refused while a predecessor is open; completion is refused while a
   mandatory field of the part is missing; reopening removes `completion`; and the specification state updated.
   - Skills: pm-dev-java:junit-core, pm-dev-java:java-core
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked` packages with
  `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a
  documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and method, license header via
  `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail, exception message,
  log message or commit metadata (the `by` of a completion is a pseudonym, never a name).
- Verify every claim about a JGit API against JGit 7.8.1 source or documentation and name the version (CLAUDE.md).
- A change of a documented behaviour (GDR-FORMS-2 and -5) and a new outcome are put to the owner through the question tool BEFORE the code.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit, `cui-test-generator`, JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok,
  `cui-java-tools`, the JSON Schema validator and the YAML reader `git-forms` already uses. Any other library is put to the owner BEFORE it is added.
- `git-store` stays read-only; an item that needs a change there is reported in the PR and the inbox message and put to the operator (the pinned
  release of PLAN-13 was approved this way once).
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- When the module gains behaviour, update the "Current state" paragraph of `AGENTS.md`, `doc/concepts/module-structure.adoc` and the
  specification document in the same commit.
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S SELECTION — the
  question tool names each finding key, the progress record records the selection as `operator-selected: {issue-key}: {reason}`, and the key is
  transmitted through `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`.
  The lane never records a dismissal of its own; a dismissal the run cannot transmit is a blocker. The merge gate reads the open-finding count at
  the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks.
- Review lessons of PLAN-13 (`landings/PLAN-13.md`): every read-modify-write of a reference is a compare-and-swap on the id that was read; a
  release is pinned to the commit that was validated; every number is emitted in canonical non-exponential form; every untrusted input is bounded
  in size and depth; a rule that a spec states needs a test that fails when the rule is removed.

## Done when

- `verify` passes at the plan's head with each of the five cases of deliverable 5 covered by a test naming the requirement id, and the decision of
  deliverable 1 recorded in the concept and the specification.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is `0` or every key is
  operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: GDR-FORMS-2 checks `required` on the whole document at the transition out of the first state and at every release, and the workflow
  has no declaration for stages — read at `doc/specification/git-forms.adoc` 175-181 (the whole-document check) and the grep of `stage` over
  `git-forms.adoc` and `schema-driven-documents.adoc`, which finds no such concept, at `3e3cfc4`.
  - verdict: corroborated | checked_at: 3e3cfc4b738f3606378263cb7da5cb12118ff59e | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 3e3cfc4: git-forms.adoc 175-181 states that a transition leaving the first state and every release must also check required (the whole document, naming the JSON Pointers of the missing fields); grep of stage over git-forms.adoc and schema-driven-documents.adoc finds no stage concept
- OBSERVED: `required` under `oneOf`, `anyOf`, `not`, `if`, `then`, `else` and `contains` stays refused — read at `doc/specification/git-forms.adoc` 391
  at `3e3cfc4`.
  - verdict: corroborated | checked_at: 3e3cfc4b738f3606378263cb7da5cb12118ff59e | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 3e3cfc4: git-forms.adoc 391 says required under oneOf, anyOf, not, if, then, else and contains stays refused
- HYPOTHESIS: the consumer's description of its own side (its concept `staged-documents.adoc`, its open question 1 naming the library as the home,
  the decision of its owner) is the consumer's own and was not read here; confirm at the outline with the operator.
  - verdict: unverifiable | checked_at: 3e3cfc4b738f3606378263cb7da5cb12118ff59e | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: the consumer's own concept staged-documents.adoc and its owner decision live in cuioss/Audit-Notary and were not read; the message is the only source

## Expected Surface

- OBSERVED: `git-forms/`
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/concepts/schema-driven-documents.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (only if an outcome or a dependency row changes)
- HYPOTHESIS: `git-forms/doc/LogMessages.adoc` (only if a log message is added)
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/concepts/module-structure.adoc` (State column, only if the module state wording changes)

## Dependencies and Sequencing

- Depends on: PLAN-13 (shipped, PR #64, `e38e079`: the workflow, the transitions and the step-up) and PLAN-14 (deadlines, GDR-FORMS-6: the condition
  language of deliverable 2 extends its rules, and PLAN-14 deliverables 6 and 7 edit the same `StepUpPolicy`, `TransitionSave` and
  `git-forms.adoc` passages).
- Overlaps with: PLAN-14 on `git-forms/`, `git-forms.adoc` and `schema-driven-documents.adoc` (strictly sequential, PLAN-14 first) and PLAN-15 and
  PLAN-16 only on `AGENTS.md` and `module-structure.adoc`.
- Adjacent to: the consumer repository `cuioss/Audit-Notary` (the requester); the release of the stages is its prerequisite for conditional mandatory
  fields.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-27-forms-stages.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
