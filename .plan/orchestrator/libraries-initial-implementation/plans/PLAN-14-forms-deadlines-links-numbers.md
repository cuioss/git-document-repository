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
6. Step-up switch and single-factor values in GDR-FORMS-7 (a consumer request of Audit Notary, inbox message
   `audit-notary-001`, folded in on 2026-11-24 under the epic's routing rule: PLAN-13 implements step-up and is running, so
   the change lands on top of it, and PLAN-14 is the next staged plan that edits `git-forms/`). The consumer decided on
   2026-11-24 (ADR-0005 of cuioss/Audit-Notary, not read here) to make step-up configurable with one key for both of its login
   providers. Re-read at the plan's head, after PLAN-13 has merged, that `StepUpPolicy` still has no switch and the spec
   still words `step-up.amr` as multi-factor only (at `e38e079`, the merge of PLAN-13, it does: `StepUpPolicy.java` 33-74,
   `git-forms.adoc` 714-734): (a) a setting
   `step-up.enabled` (boolean, default `true`) in GDR-FORMS-7 and in `StepUpPolicy`: when it is `false`, no transition
   requires step-up, whatever the workflow declares, and `step-up.amr`, `step-up.acr` and `step-up.max-age` are not required
   (today `StepUpPolicy.of(Duration)` requires a positive `maxAge`); the workflow validation that an `intermediate-save`
   transition must not declare step-up stays as it is, also when the switch is off; (b) GDR-FORMS-7 states that the
   deployment decides which `amr` or `acr` values satisfy step-up: `mfa` stays the default, and a single method such as `pwd`
   is allowed when the deployment wants a renewed password check; the `step-up.amr` row, the `StepUpPolicy` Javadoc
   (`{@value #MFA}` paragraph, the `amr` and `acr` `@param` lines) and the two concept passages that call the setting MFA
   (`doc/concepts/identity-and-signing.adoc` § Step-Up for Approvals, `doc/concepts/schema-driven-documents.adoc` line 104 at `e38e079`) are
   reworded; the check itself (a value of `step-up.amr` in `Auth-Context` and a fresh `Auth-Time`) is unchanged. Tests, each
   naming GDR-FORMS-7: switch off (a `release` transition without step-up passes, and a policy without `max-age` is
   accepted), switch on with `step-up.amr: [pwd]` (a fresh `pwd` passes, a stale `Auth-Time` is refused), the default
   (`step-up.enabled` absent behaves as `true`), and `intermediate-save` with a declared step-up refused with the switch on
   and off.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
7. `git-forms` defects of PLAN-13's landing (`landings/PLAN-13.md` L3-1 to L3-6, L2-1, L2-2; folded in on 2026-11-24 under the epic's
   routing rule, with the operator's answer "resume" for item (a)), each first re-read at the plan's head, behaviour fixes
   reproduced by a test that fails at the base (lines are those of `e38e079`): (a) **a `release` transition whose release fails
   after its save cannot be retried through `DocumentTransitions`**: `TransitionSave.release` (129-135) leaves the saved
   `state=<to>` in place, the retry reads that state from the branch (`TransitionContext.currentState` 128-131), looks the
   transition up by name and from-state (`Workflow.transition` 372-381) and `TransitionRunner` (93-96) returns
   `TRANSITION_REFUSED`; the failures are `UNREACHABLE`, a lost lease on every attempt, `REJECTED`, an `INCOMPLETE` tag push,
   signing `REFUSED` and the pin `CONFLICT`, and the branch then says `approved` while `main` and the tag do not. Decided by the
   operator on 2026-11-24 (resume): a `release` transition whose document already stands in its target state, and whose release
   is not on `main` (read from the clone, not assumed), performs the pinned release of the commit that holds the state, with the
   step-up check unchanged and no second state write; record the rule in `git-forms.adoc` § GDR-FORMS-5 beside "no compensating
   write", and decide at the outline what a retry of a transition that is not a release does (it stays refused); tested for each
   failure above through `DocumentTransitionsTest` (today `failedReleaseKeepsTheSavedState` 702-724 asserts only that the state
   stays), and for a retry whose branch moved in between (the pin refuses it; nothing written). It relies on the behaviour of
   PLAN-25 for the tag-only recovery path of the store: where PLAN-25 has not shipped, the test of that sub-case is marked and
   the dependency recorded in the PR; (b) **`DocumentTemplate` charges one operation for a fragment node that copies and scans
   the whole fragment** (`render` 264-273: `lineFeeds` twice and `withoutTrailingLineFeeds`), so a template that places
   `{{>name}}` inside a section over a long data list with a large whitespace-only narrative file (up to 16 MiB, adding nothing to
   the output, so the output limit never trips) costs up to 1,000,000 scans of 16 MiB in one save attempt under the store's locks
   or in the CI check: charge the fragment's length to the operation budget (or normalise each fragment once per render), tested
   with a scaled-down case that fails at the base on the counted work, and record the bound in `git-forms.adoc`; (c) **the input
   bound of the save differs from that of the check**: the step reads fragments, parts and data whole through the store's attempt
   view (`ExplicitSave` 905-906, `getBytes(Integer.MAX_VALUE)`, all `narrative/*.adoc` of the unit, also those no template
   references) while `OutputCheck` (384-386) refuses any file over `MAX_FILE_BYTES` (16 MiB) unread, so a save that commits a
   17 MiB fragment passes and `OutputCheck` / `VerifyOutput` then return `MALFORMED` for the whole commit: bound the size of what
   the step reads to the check's bound and refuse the save with a result before it commits (in `git-forms`; if only a `git-store`
   change can do it, report it in the PR and let PLAN-25 or the operator decide), tested with a file one byte over the bound;
   (d) **a split part at the 128-level nesting limit throws instead of returning a result**: `DocumentLayout.join` (279-302)
   puts the part under a top-level key, so the joined data reach depth 129, and `DataNode.requireDataModel` (126-142) throws an
   `IllegalArgumentException` that `OutputCheck.check` does not declare (against "expected outcomes are results", AGENTS.md),
   that `VerifyOutput` (184-188) reports as `NOT_A_REPOSITORY git-dir: not a repository` (a wrong diagnostic) and that ends a
   save as `FAILED` (`ExplicitSave` 404-409): return `MALFORMED` from the check and the step, correct the diagnostic, tested
   with a part nested at the limit (the chain through `OutputCheck` and `DocumentTemplate` was read by the analysis leaf only:
   re-read it first); (e) tests and wording: no test puts stale or foreign content at the output path and shows that the save
   replaces it ("never merged", only the unchanged self-loop at `DocumentTransitionsTest` 830 is covered); `DocumentLayout.isOutput`
   (185) has no production caller (tests only), so either the step uses it or the Javadoc "the one file never merged" is moved to
   the step; `VerifyOutput` (184) prints a mismatching path from a tree entry without escaping, so a path with a newline or a
   control character splits the line for a CI consumer: escape or refuse it; `Workflow` (168-170) refuses a duplicate transition
   name, which the spec text does not say (the spec's own example needs no such construct): state it in `git-forms.adoc`; and the
   owner decision recorded in the specification that an `auth_time` after `now` counts as not older (`StepUpPolicy` 95, tested at
   `StepUpPolicyTest` 148) is kept, with the reason named: the caller's duty to pass validated claims, unbounded otherwise; (h) **added on 2026-11-25 from the analysis of PLAN-22
   (`landings/PLAN-22.md` L3-2, L2-1), each re-read at the plan's head (lines are those of `c22700a`): `TransitionSave.release` (124-136) returns
   `Performed(release.commitId(), release)` for any `OK` and never reads `Release.branchMovedOn`** (a grep of `git-forms/src` is empty), although its
   Javadoc and `DocumentTransitions` (64) say the release "publishes exactly that commit": after the store's tag-only recovery (an earlier release
   of the same version left `main` merged without its tag; `DocumentRelease` 281-288, PLAN-25 deliverable 1) a pinned release completes the earlier
   release's tag and returns `OK` with `branchMovedOn=true`, so a transition reports `Performed` although its saved state was not released: map
   `branchMovedOn` to a result of the transition that says the state is saved and not released (the outcome, a new constant or an existing one, is put
   to the owner; PLAN-25 decides what the store returns), tested with a scripted store; and `CloneReader.mode` (190-212), the
   `SaveStep.Attempt.mode` implementation kept compiling by PLAN-22, has no test (a grep of `git-forms/src/test` for `TreeEdit.Mode` is empty): test
   it with a gitlink and a regular file.
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
- Every behaviour fix of deliverable 7 has a test that fails at `e38e079` (`main` after PLAN-13, where the defects are present) and
  passes at the plan's head; a failed `release` of each kind listed in (a) is retried through `DocumentTransitions` and ends
  with the release on `main` and the tag, or with the passed-on failure and nothing written; an item whose premise the re-read at
  outline refutes is dropped.
- Deliverable 6: `step-up.enabled: false` makes a `release` transition without step-up pass, and `step-up.amr: [pwd]` accepts a
  fresh `pwd` and refuses a stale `Auth-Time`, each by a test naming GDR-FORMS-7; the `step-up.amr` row and the two concept
  passages no longer say the values must denote multi-factor authentication.

## Claim Labels

- OBSERVED: further forms of a deadline rule are an open question of `doc/concepts/schema-driven-documents.adoc` §
  Open Questions; this plan implements the three forms GDR-FORMS-6 names and no more.
- OBSERVED: numbered entries use a ULID draft reference until the first save — read at
  `doc/specification/git-store.adoc` § GDR-STORE-2 and `doc/specification/git-forms.adoc` § GDR-FORMS-9.
- OBSERVED: GDR-FORMS-7 has no switch for all transitions and words `step-up.amr` as multi-factor only — read at
  `doc/specification/git-forms.adoc` lines 542-570 at `7531b72`; PR #64 (PLAN-13) adds `StepUpPolicy` (`amr`, `acr`, `maxAge`)
  without an `enabled` component and with the same wording.
  - verdict: corroborated | checked_at: e38e079c0f9c7d5f8fae882a743c5978117ced90 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at e38e079 (PLAN-13 merged): StepUpPolicy.java 42 is record StepUpPolicy(amr, acr, maxAge) with no enabled component, Javadoc 33 words the amr values as multi-factor; git-forms.adoc 714-734 has per-transition step-up only, max-age required, amr row multi-factor only; identity-and-signing.adoc 93-106 and schema-driven-documents.adoc 104 word the setting as MFA

## Expected Surface

- HYPOTHESIS: `git-forms/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-03 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)
- OBSERVED: `doc/concepts/identity-and-signing.adoc` (§ Step-Up for Approvals, lines 90-103, calls the setting MFA; deliverable 6)
- OBSERVED: `doc/concepts/schema-driven-documents.adoc` (line 104 at `e38e079`, step-up as an MFA authentication; deliverable 6)

## Dependencies and Sequencing

- Depends on: PLAN-13; PLAN-09 (index). Deliverable 5 (PLAN-23's leftovers, folded in on 2026-11-24 at the operator's routing rule) adds
  no file surface: every file it names lies under `git-forms/` and `doc/specification/git-forms.adoc`, both declared. Deliverable 6
  (step-up switch, folded in on 2026-11-24 from `audit-notary-001`) adds the two concept documents above to the surface.
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
