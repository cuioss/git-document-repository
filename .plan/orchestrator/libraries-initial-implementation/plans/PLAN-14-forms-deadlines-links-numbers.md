# PLAN-14: git-forms — deadlines, typed links, running numbers

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Finish `git-forms`: deadlines per state (`within`, `review-interval`, `field`) computed from the index and never
stored (GDR-FORMS-6), typed links `<alias>:<kind>/<id>[#fragment]` and `<catalog>:<id>` checked on save with retired
targets still resolving (GDR-FORMS-8), and running numbers — ULIDs for per-document kinds, readable zero-padded numbers
from the collection's counter file for numbered kinds, never duplicated under concurrency (GDR-FORMS-9).

## Deliverables

1. Deadlines (GDR-FORMS-6): the three rule forms, the list of documents due at or before an instant from the index; no
   stored deadline data.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
2. Typed links (GDR-FORMS-8): `"format": "gdr-link"` fields, parsing of both forms, existence check on save through
   the index (documents) or a catalog-resolution interface (catalogs — implemented by `git-directory`, PLAN-15),
   missing target → invalid, fragment not interpreted, retired targets resolve.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Running numbers (GDR-FORMS-9): ULID for per-document kinds; numbered entries keep a ULID draft reference until
   their first save, then take prefix + zero-padded counter from `<collection>/.counter` in the same commit; the
   loser of a concurrent creation re-reads the counter and takes the next number.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests: deadline forms at boundaries, link check incl. retired and fragment cases, concurrent numbered creation
   (threads against a local remote) never yielding the same number; specification state updated ("git-forms
   implemented").
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
5. `git-forms` leftovers of PLAN-23's landing (`landings/PLAN-23.md` L3-1 to L3-6, L2-1 to L2-4; folded in on 2026-11-24 under the
   epic's routing rule, because PLAN-14 is the next staged plan that edits `git-forms/`), each first re-read at the plan's head,
   behaviour fixes reproduced by a test that fails at the base (lines are those of `bdbb5e4`): (a) **`DataNode.asObject` and
   `asArray` (307, 325) build a pointer string per child by copying the parent pointer**, so a document of at most 3 Mi code points
   with a chain of long keys at depth and one wide mapping at the bottom, validated under a map-of-maps schema
   (`additionalProperties` recursing through `$ref: "#"`), costs gigabytes of strings and ends in an `OutOfMemoryError`: build the
   pointer lazily or share the prefix, or bound the product of key length and depth, and record the bound in `git-forms.adoc`;
   the size is not shown by reading, so the test measures a scaled-down case; (b) `SchemaChecks.holdsRequired` (186) recurses once
   per link of a `properties` or `items` to `$ref` chain, which the in-place bound (`InPlaceCycles` 60) does not cover: a
   `StackOverflowError` from `FormSchema.parse` for a schema of about 60,000 flat `$defs`: make it iterative or bound the chain and
   return `MALFORMED`, and record the limit; (c) the stack depth is data depth times the in-place chain per level
   (`MandatoryFields` 65 and the library's `RefEvaluator`): bound the product or record the limit in `git-forms.adoc`, tested
   with a long in-place chain at the data depth limit; (d) the reader counts the digits of a hexadecimal integer in the source
   radix (`YamlReader` 395) and the encoder counts the decimal digits (`YamlDocumentCodec` 161), so `decode` accepts
   `0x` plus 831 to 1000 hexadecimal digits and `encode(decode(content))` throws: count in one radix on both sides, tested as a round
   trip at the bound, with a sign, with a long mantissa and a tiny exponent, and with a `multipleOf` of a large exponent;
   (e) `DataNode.requireDataModel` (126) walks a shared-subtree value as a tree (`x = List.of(x, x)` built 128 times is 2^128
   visits, a hang): visit each shared node once, or refuse sharing as a result, and decide whether a hostile in-memory value ends in a
   result and not in an `IllegalArgumentException` (the spec of PLAN-12 never said), recording it in `git-forms.adoc`; (f)
   `KindConfiguration` (248) keys the collection namespace on the registry alias only, so two aliases for one physical repository
   pass the duplicate check: read whether `RepositoryRegistry` refuses duplicate targets and compare physical repositories, tested;
   (g) tests and wording: `NestingDepthTest` (148, 159) asserts `IllegalArgumentException` for the in-memory 100,000 cases, has no
   test of a self-containing `Map` or `List`, never combines depth with a chain, uses nested arrays instead of real sub-schemas
   through `withoutRequired`, `Shape` and `checkSubSchemas`, and tests flow-style nesting only; `NumberBoundsTest` (107) never
   encodes the accepted 1000-digit hexadecimal; `KindConfigurationTest` (536) has no test of two aliases for one repository;
   `git-forms.adoc` (242) does not record that the non-in-place `properties`/`items` to `$ref` chain is unbounded for
   `holdsRequired` and says "one marker remains" where `NestingDepthTest` (143) added a second `cui-rewrite:disable`; the
   preparation counter of `SchemaValidatorPreparationTest` (67) is a static `AtomicInteger`.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:java-security
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

- `verify` passes at the plan's head with GDR-FORMS-6/8/9 each covered by a test naming the requirement id; every
  GDR-FORMS requirement is then covered.
- Every behaviour fix of deliverable 5 has a test that fails at `bdbb5e4` (`main` after PLAN-23, where the defects are present) and
  passes at the plan's head; an item whose premise the re-read at outline refutes is dropped from the deliverable.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: further forms of a deadline rule are an open question of `doc/concepts/schema-driven-documents.adoc` §
  Open Questions; this plan implements the three forms GDR-FORMS-6 names and no more.
- OBSERVED: numbered entries use a ULID draft reference until the first save — read at
  `doc/specification/git-store.adoc` § GDR-STORE-2 and `doc/specification/git-forms.adoc` § GDR-FORMS-9.

## Expected Surface

- HYPOTHESIS: `git-forms/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-03 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-13; PLAN-09 (index). Deliverable 5 (PLAN-23's leftovers, folded in on 2026-11-24 at the operator's routing rule) adds
  no file surface: every file it names lies under `git-forms/` and `doc/specification/git-forms.adoc`, both declared.
- Overlaps with: WS-03 plans — strictly sequential.
- Adjacent to: catalog resolution — the interface is defined here, its implementation is `git-directory` (PLAN-15).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-14-forms-deadlines-links-numbers.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
