# PLAN-23: git-forms hardening (the leftovers of PLAN-12, two of them major)

epic: libraries-initial-implementation
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Close the two major defects and the minor residue that the three-layer analysis of PLAN-12 (`landings/PLAN-12.md`) found in the
shipped `git-forms` module, before PLAN-13 to PLAN-16 build on it. Both majors are reachable by content a user can commit:
a document nested deeper than the process's stack ends `validate`, `required` or `encode` in a `StackOverflowError` instead of a
result (GDR-GEN-3), and one decimal scalar with an exponent near `int` stalls or throws in the library's `multipleOf`. The minors
are a codec key order that ignores `$ref` and `allOf`, a `format` keyword that is silently not asserted and not documented,
unchecked kind configuration (a duplicate `collection`, a free `numbering.prefix`, free paths, an unbounded file read), a validator
rebuilt on every call, YAML 1.1 interoperability, stale wording, an unrecorded dependency decision, and thin tests. Operator's
decision of 2026-11-22: a new plan, emitted at once and ahead of PLAN-13.

## Deliverables

1. Hostile input ends as a result (the two majors of `landings/PLAN-12.md`, L3-1 and L3-2), each item first reproduced by a test
   that fails at the base: (a) **nesting depth.** `YamlReader` is iterative and accepts a document nested up to the 3 Mi
   code-point limit (`a: [[[[…` 100,000 times), but `DataNode.requireDataModel` (103), the pointer building of `DataNode`,
   `YamlDocumentCodec.ordered` (136), `YamlWriter.mapping` and `sequence` (79 and 108), `MandatoryFields.collect` (65) and the
   library's own validator recurse over it, so `validate`, `required` and `encode` end in a `StackOverflowError` (an `Error`, not
   a result); the schema side has the same recursion (`SchemaResources.index` 100, `FormSchema.withoutRequired` 239,
   `Shape.of` 343) and the Javadoc of `schemaObjects` claims a deep schema cannot overflow, which only that walk guarantees.
   Bound the depth at one compiled limit, stated in `doc/specification/git-forms.adoc`, enforced where data enters (the reader,
   and `DataNode.root` for an in-memory value a caller built) and for a schema where it is loaded, refused as a result that names
   the limit and never the content; or make every recursive site iterative. Tests: a value at the limit passes, one level deeper
   is refused as a result, and a 100,000-deep value ends as a result in `validate`, `required`, `encode` and the layout, with no
   `Error`. (b) **decimal exponent.** `YamlReader.decimal` (237) refuses only an exponent beyond `int` (the
   `NumberFormatException` of `new BigDecimal`), so `amount: 1e2147483647` is accepted, and under a schema with
   `"multipleOf": 0.01` the library's `BigDecimal.remainder` (`Evaluators.MultipleOfEvaluator`, json-schema 1.9.1) stalls or ends
   in an `ArithmeticException` that escapes `validate`; `git-forms.adoc` (200-216) calls this an accepted gap of the library, but
   one scalar can stall a save. Bound the magnitude of the exponent and the digit count of a decimal in the reader (the bound
   stated in `git-forms.adoc`, refused as a result), and check the digit count of an integer scalar BEFORE `new BigInteger`
   (`YamlReader.integer`, 257, parses up to 3 Mi digits before `DataNode`'s 1000-digit rule applies); test `1e2147483647`, the
   bound and the bound plus one under `multipleOf` 0.01, and a 3 Mi-digit integer, each ending in bounded time as a result; keep
   the documented underflow below about `1e-308`, pinned by a test, which nothing tests today.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:java-security
   - Persona: implementer
2. Minor defects, wording and tests (`landings/PLAN-12.md` L1-1, L1-2, L2-1 to L2-8, L3-3 to L3-10), each first re-read at the
   plan's head and reproduced where it is behaviour: (a) the codec key order follows the schema only for inline `properties`,
   `prefixItems` and `items` (`FormSchema.Shape.of`, 343): a property declared by a local `$ref` or an `allOf` branch gets its
   nested keys in lexicographic order, against "the key order of the schema" of GDR-FORMS-3: resolve a local `$ref` and merge
   `allOf` branches in declaration order, or state the limit in `git-forms.adoc` with a test that pins it; (b) `format` is never
   asserted: `Draft2020Dialect` of json-schema 1.9.1 treats it as an annotation and no `FormatEvaluatorFactory` is registered
   (`SchemaValidator.java` 195 has an unreachable branch for it), so a schema `"format": "date"` or `"email"` is silently not
   enforced; state in `git-forms.adoc` and the module documentation that it is an annotation (the `gdr-link` check of GDR-FORMS-8
   is PLAN-14), or register the format evaluators, which is new behaviour the owner decides; either way a test pins it; (c) a
   duplicate `collection` is not refused (`KindConfiguration.java` 196 refuses only a duplicate `id`): a numbered kind with
   collection `x` (`refs/heads/x`) and a per-document kind with collection `x` (`refs/heads/x/<ulid>`) is a git directory/file
   conflict, and two kinds of one repository and collection share one namespace: refuse the pair at load, tested; (d)
   unchecked configuration values (`KindConfiguration.java` 229): `numbering.prefix` accepts `/`, `..`, `:`, `#`, whitespace and
   newlines though it names tags and links (GDR-FORMS-9), `digits` up to 2147483647, and `schema`, `template` and `workflow`
   accept `..` segments, a leading `/` and NUL: bound them (a charset safe for a reference name and a link, an upper bound for
   `digits`, relative paths without `..`), tested; (e) the kinds file is read whole before any size limit applies
   (`Files.readAllBytes`, a symlink followed, and `getBytes(Integer.MAX_VALUE)` for a blob): bound the size first; (f)
   `SchemaValidator.prepare` (127) builds a new `ValidatorFactory` and validates the schema against the meta-schema on every
   `validate` call and again for each use inside `required`, and `satisfies` re-walks the data per candidate (quadratic in the
   depth): prepare once per `FormSchema` (thread-safe), tested by a test that counts preparations; and the `constraint(...)`
   lookup of a message number resolves the library's schema location against the root, so for a keyword inside an embedded
   `$id` resource it can read the wrong node and print the limit of the schema or a neighbouring number: reproduce, then fix or
   record; (g) YAML 1.1 interoperability: the writer emits `no`, `yes`, `on`, `off`, `2026-11-21` and `12:30` plain (correct under
   the 1.2 core schema), which a 1.1 reader (PyYAML, SnakeYAML 1.x, a CI tool) reads as booleans, dates or sexagesimal numbers:
   state it as an interoperability limit in `git-forms.adoc`, or quote them, which changes byte output and is the owner's
   decision, and add the round-trip cases of date-like strings and the 1.1 words to the codec test; (h) `Violation.pointer`
   carries property names from the data, so a schema with `patternProperties` or `additionalProperties` keyed by user data (an
   e-mail address, a person name) prints that key: state the limit and assert it in `ValidationSecretsTest` with a data-keyed
   schema (the current assertions cover instance values only); (i) stale wording: the `package-info` Javadoc, `git-forms.adoc`
   line 8 ("it holds so far its closed outcome list …") and `doc/README.adoc` ("exists with its outcome list and its result")
   still describe the module before PLAN-12; (j) the dependency record: deliverable 1 of PLAN-12 asked for the YAML library and
   the validator "with alternatives", and `git-forms.adoc` and `general.adoc` § GDR-GEN-1 name only the chosen ones
   (snakeyaml-engine 3.2, json-schema 1.9.1, owner approval 2026-11-21): put the alternatives and the reasons to the owner
   through the question tool and record them, because the record cannot be reconstructed from the repository; (k) the validator
   is an automatic module (`requires dev.harrel.jsonschema`) and the tests run on the class path (`useModulePath=false`), so a
   module-path run of `git-forms` is never exercised: add one module-path test run or record the limit; (l) tests that are
   missing: deep nesting (deliverable 1), `multipleOf`, an object defined by `$ref`, a duplicate collection, the `~` and `/`
   escaping of the `Violation` pointer produced by `validate` (only `required()` pointers are asserted), and
   `NativeImageMetadataTest` asserting that every resource actually loaded is listed in `resource-config.json` (it checks only
   that the listed ones resolve) and that snakeyaml-engine needs no reflection metadata; (m) suppressions: three main
   `@SuppressWarnings("unchecked")` (`FormSchema` 159, `KindConfiguration` 236, `YamlDocumentCodec` 131), one
   `// cui-rewrite:disable InvalidExceptionUsageRecipe` (`SchemaValidator` 119) and eight `unchecked` in tests: fix the casts or
   record each with its reason (a Sonar dismissal is the operator's, see the constraints).
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:javadoc, pm-documents:ref-asciidoc
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
- Review lessons of PLAN-08 for tests and Sonar (`landings/PLAN-08.md`): a test asserts content, not only presence, and
  asserts exact names where names are the contract; a fixture value a test relies on to differ is distinct by construction,
  not from independent random draws; a concurrent scenario ends with one deadline, cancellation and `shutdownNow` with
  `awaitTermination`; one throwing call per `assertThrows` lambda, no unused `throws` (Sonar S5778, S1130); a hand-kept
  catalogue (`LogMessages.adoc`) is checked against its source by a test; never attach a throwable to a log record on a
  path promised sanitised (GDR-NFR-3, DEBUG included).

- A limit is a compiled bound, stated in `doc/specification/git-forms.adoc` with its reason, refused as a `FormsResult` /
  `StoreResult` that names the limit and never the content or a value (GDR-NFR-3), and tested at the bound and one beyond it.
  `git-store` and `git-core` stay read-only: a gap there is reported in the PR and the inbox message, not absorbed.
- Every behaviour fix is first reproduced by a test that fails at the base and passes at the head; an item whose premise the
  re-read at outline refutes is dropped from the deliverable that names it. An item that ends in an owner decision (2 (b), (g),
  (j)) is put to the owner BEFORE the work, not after it.
- Review lessons of PLAN-08 and PLAN-09 (`landings/PLAN-08.md`, `landings/PLAN-09.md`) bind the tests: assert content and exact
  names, take fixture values that differ by construction, end a concurrent scenario with one deadline, cancellation and
  `shutdownNow` with `awaitTermination`, one throwing call per `assertThrows`, a hand-kept catalogue checked against its source,
  never attach a throwable to a log record on a sanitised path (DEBUG included).

## Done when

- Every behaviour fix of deliverables 1 and 2 has a test that fails at `8d09629b70e4c9a4fca68df5a744531c7b4db7f6` (`main` after
  PLAN-12, where the defects are present since PLAN-12) and passes at the plan's head.
- `verify` passes at the plan's head with GDR-FORMS-1/2/3 each still covered by a test naming the requirement id.
- Every item of deliverable 2 is either fixed and tested at the plan's head, or recorded as an accepted exception or an owner
  decision with its reason in `doc/specification/git-forms.adoc`; no document lists an outcome the code does not return.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is
  `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: no nesting-depth limit exists in `git-forms` main code (a search for `depth`, `nesting` and `StackOverflow` finds
  only the comments of `InPlaceCycles`), and the data is walked recursively by `DataNode.requireDataModel`
  (`git-forms/src/main/java/de/cuioss/git/document/forms/DataNode.java` 103-117), `YamlDocumentCodec.ordered` (136-150) and
  `YamlWriter.mapping` and `sequence` (79-115) — read at `8d09629`.
  - verdict: corroborated | checked_at: 8d09629b70e4c9a4fca68df5a744531c7b4db7f6 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 8d09629 (git-forms unchanged since): grep for depth|nesting|StackOverflow in git-forms main finds only InPlaceCycles comments; DataNode.java 103-117 requireDataModel recurses through list.forEach and map values; YamlDocumentCodec.java 136-150 ordered recurses; YamlWriter.java 79-115 mapping and sequence recurse; MandatoryFields.java 65 collect recurses
- OBSERVED: `YamlReader.decimal` accepts every exponent that `new BigDecimal(text)` accepts, which refuses only an exponent
  beyond `int` (`YamlReader.java` 237-246), and `git-forms.adoc` (200-216) states that `multipleOf` computes
  `BigDecimal.remainder` and "can stall the validation, and no node can prevent that" — read at `8d09629`.
  - verdict: corroborated | checked_at: 8d09629b70e4c9a4fca68df5a744531c7b4db7f6 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 8d09629: YamlReader.java 237-246 decimal returns new BigDecimal(text) and refuses only the NumberFormatException of an exponent beyond int; git-forms.adoc 200-216 says only multipleOf is left to the library, that BigDecimal.remainder precision grows with the exponent difference and that data with a large exponent can stall the validation
- OBSERVED: `KindConfiguration.java` 192-198 refuses only a duplicate kind `id`, `FormSchema.Shape.of` (343-350) reads only the
  inline `properties` of a schema, and `SchemaValidator.prepare` (127) builds a new `ValidatorFactory` per call — read at
  `8d09629`.
  - verdict: corroborated | checked_at: 8d09629b70e4c9a4fca68df5a744531c7b4db7f6 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 8d09629: KindConfiguration.java 192-198 byId.putIfAbsent refuses a duplicate id only; FormSchema.java 343-350 Shape.of reads map.get(PROPERTIES) of the schema itself; SchemaValidator.java 126-130 prepare builds a new ValidatorFactory per call
- HYPOTHESIS: `format` is not asserted by `Draft2020Dialect` of json-schema 1.9.1, the writer emits the YAML 1.1 words plain, and
  `numbering.prefix`, `schema`, `template` and `workflow` are unchecked at load — confirm/refute at `SchemaValidator.java` 195,
  `YamlWriter.java` and `KindConfiguration.java` 229 and the json-schema 1.9.1 sources (verify-at-outline).

## Expected Surface

- OBSERVED: `git-forms/src/main/java/de/cuioss/git/document/forms/`
- OBSERVED: `git-forms/src/main/java/module-info.java`
- OBSERVED: `git-forms/src/main/resources/META-INF/native-image/`
- OBSERVED: `git-forms/src/test/java/de/cuioss/git/document/forms/`
- OBSERVED: `git-forms/pom.xml`
- HYPOTHESIS: `git-forms/doc/LogMessages.adoc` (only if a log message is added)
- OBSERVED: `doc/specification/git-forms.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (GDR-GEN-1 dependency record, the GDR-GEN-3 table if an outcome changes)
- HYPOTHESIS: `doc/README.adoc` (the stale "exists with its outcome list" sentence)
- HYPOTHESIS: `AGENTS.md` ("Current state", if a public type or method changes)

## Dependencies and Sequencing

- Depends on: PLAN-12 (shipped, PR #54, `8d09629`, 2026-11-22). Strictly sequential with the WS-03 plans: it runs BEFORE PLAN-13,
  which builds on `DataNode`, the codec and `required()` and would otherwise inherit both majors. PLAN-13 depends on it.
- Not held back: the operator released it on 2026-11-22 for emission beside PLAN-10 (they share `doc/specification/general.adoc` and
  `AGENTS.md`, plus the `doc/README.adoc` sentence, a three-file overlap: the operator accepted the pairing on the two, the gate shows the third). `git-store` is untouched, so PLAN-10's `git-store/` work does not collide
  with it; whichever of the two lands second rebases onto the other's documents.
- Overlaps with: PLAN-10 on `general.adoc`, `AGENTS.md` and `doc/README.adoc`; every WS-03 plan.
- Adjacent to: `git-store/` — consumed, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-23-forms-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
