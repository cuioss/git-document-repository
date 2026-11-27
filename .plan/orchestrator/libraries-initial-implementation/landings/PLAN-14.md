# Landing Analysis: PLAN-14 — git-forms: deadlines, typed links, running numbers

epic: libraries-initial-implementation
workstream: WS-03
pr: #81 (https://github.com/cuioss/git-document-repository/pull/81), squash-merged as `0187cf3df10512aa71b2a19ea950c11654588303` on 2026-11-27

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-27 from the landing message
> `plan-14-forms-deadlines-links-numbers-023` (the plan's inbox message), corroborated against git, the PR through the CI
> abstraction and the merged code. The message is a lead, not a fact; what follows states what was checked.

## Deliverable Fidelity vs Spec

The spec staged seven deliverables (`deliverables_total=7`, `deliverables_done=7`). All seven trace to code and to tests. The spec constraint "no
`git-store` change" was lifted for one accessor at the operator's decision (see D1/D7 and the routing section).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Deadlines (GDR-FORMS-6): the three rule forms, the list of documents due at or before an instant from the index; nothing stored | shipped-as-specified | `Deadlines.java` 167-182, 243-269, 305-352; `DeadlinesTest`, `DeadlineBoundariesTest` (within, review-interval, field at -1 ms, at, +1 ms, re-entry, self-loop, no writes). The index alone cannot hold the entering instant, so the code walks the history of the state (L1-4). |
| 2. Typed links (GDR-FORMS-8): `"format": "gdr-link"`, both forms, existence check on save | shipped-as-specified | `TypedLink`, `LinkTargets` 96-118, `LinkFields`, `CatalogResolver`, `SchemaValidator.evaluate`; `TypedLinkTest`, `TypedLinkCheckTest` (missing, fragment, catalog, `$ref`/`allOf`, no link text in details or logs). Gaps in L2-1. |
| 3. Running numbers (GDR-FORMS-9): ULID for per-document kinds, entries keep a ULID draft reference until the first save | shipped-as-specified | `RunningNumbers` 40-93, `TransitionStep.allocate`, `TransitionContext.allocation`; `RunningNumbersTest`. The concurrency test never forces a loser (L2-2). |
| 4. Tests: deadline forms at boundaries, link check incl. retired and fragment cases, concurrent numbered creation | shipped-modified | exist for all three; the retired-entry case is proven only by a stub resolver and the concurrent creation only by chance (L2-1, L2-2). |
| 5. PLAN-23 leftovers (L3-1..L3-6, L2-1..L2-4) | shipped-modified | `DataNode`, `MandatoryFields`/`holdsRequired` (iterative), `YamlReader`, `KindConfiguration`, `SchemaValidatorPreparationTest`; (b), (d), (e) hold; (a), (c), (f), (g) are weaker than the item names (L2-3..L2-6). |
| 6. Step-up switch and single-factor values in GDR-FORMS-7 | shipped-as-specified | `StepUpPolicy` (`disabled`, `requires`, `step-up.enabled`), `StepUpPolicyTest` 62-111, `WorkflowStepUpTest` 104, `DocumentTransitionsTest.releaseWithStepUpSwitchedOff`. Default stays MFA; ADR-0005 of the consumer was not edited and is flagged for it. |
| 7. PLAN-13 and PLAN-22 findings, incl. the major resume of a failed release and the `branchMovedOn` mapping | shipped-modified | `FormsOutcome.SAVED_NOT_RELEASED`, `TransitionContext.resumable`/`unreleasedTip`, `TransitionSave.resumeRelease`, `CloneReader`; `DocumentTransitionsReleaseRetryTest` 105-184 scripts the failures. (c) was narrowed at the outline (the unbounded read is gone since `c22700a`; only a test and wording remain, and the test asserts too little, L2-7). The resume itself carries two majors (L3-1, L3-2). |
| (spec constraint) no change to `git-store`; no change to `.github/project.yml` | **shipped-modified** | one public accessor `StoreIndex.sourceEntries(source, collection)` with `StoreIndexTest` and its description in `git-store.adoc`, at the operator's decision (review finding 7f13a5: two reads of one atomically published index are not one read). `.github/project.yml` untouched. |

Added-unplanned: `doc/specification/general.adoc` (a table row `FormsOutcomeSpecificationTest` requires for `SAVED_NOT_RELEASED`), `StoreIndexTest.java`,
`git-store.adoc` (the three outliers the narrative names, all borne out), and, not named by the narrative as such,
`doc/concepts/git-as-document-persistence.adoc` (D6 rewording, operator's decision at the outline), the removal of the public `DocumentLayout.isOutput` (D7(e)
third option) and the review fixes of fix tasks 15 to 18.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one read-only `execution-context-level-3` leaf on the
`orchestrator.analyze` surface (resolved level `level-3`, source `plan.effort`), with its own context. The reading is **by reading, no build, no test run,
no write**: what only a run could show is tagged `not shown by reading`. The orchestrator re-read the points marked **re-read** at their `file:line` in the
primary checkout at `0187cf3`; every other point is the leaf's report and is recorded as a lead, not as a re-read fact.

- **Dispatch:** one read-only leaf per landing — reason: reading 65 changed files (5,703 insertions) is the depth the dispatch rule exists for, and this landing's
  read shares no context with another's.
- **Verdict:** layer 1 findings (five minors); layer 2 findings (eight, all minor or info, test gaps and unexplained files); layer 3 findings — **two majors
  (L3-1, L3-2)**, seven minors and four points not shown by reading.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | D6 premise holds at the base `46fb8f4`: `StepUpPolicy` was `record(amr, acr, maxAge)` with no switch and an MFA-only Javadoc | checked | confirms | spec 74-76 (leaf; claim 2 of the spec is already `corroborated` at `e38e079`) |
| L1-2 | D7(c) premise is stale: the unbounded read it cites is gone since `c22700a` (16 MiB bound) | checked | refutes | spec 110-116; `CloneReader.java` 193-208 (leaf) |
| L1-3 | D6 names two concept passages that call step-up MFA; a third did too (`doc/concepts/git-as-document-persistence.adoc` 51-54) and the Expected Surface omits it | checked | refutes | spec 81-83 (leaf) |
| L1-4 | The objective says deadlines are computed "from the index", but `within` and `review-interval` need the entering instant, which the index cannot hold; the spec never states the history walk | checked | refutes | spec 12-13; `Deadlines.java` 243-269 (leaf) |
| L1-5 | The Done-when requires each fix test to fail at the base commit, but no deliverable or step arranges the red run; the narrative says it never ran | checked | refutes | spec 178-183 (leaf) |
| L1-6 | Done-when "verify passes" and "Sonar open count 0" | not_shown_by_reading | none | the narrative reports them; CI and Sonar state corroborated below |

**Layer 1 verdict:** findings — 5 (all minor); none persists a corpus verdict (the spec is of a shipped plan, and claim 2 stands `corroborated`).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| D1 | `Deadlines.java` 167-182 | `DeadlinesTest`, `DeadlineBoundariesTest` | none | checked, confirms |
| D2 | `LinkTargets.java` 96-118 | `TypedLinkTest`, `TypedLinkCheckTest`. **L2-1:** no test puts a `gdr-link` under `anyOf`/`oneOf`, in `items`, under `$dynamicRef` or in a split part; "retired entry resolves" only against a stub that returns true (`TypedLinkCheckTest.java` 93-98) | none | checked, minor |
| D3 | `RunningNumbers.java` 40-93 | `RunningNumbersTest`. **L2-2:** `ConcurrentNumberingTest` 114-167 asserts distinct numbers but never that a creator lost the race and re-read the counter; its retry loop hides `REJECTED`; the loser path (`TransitionStep.java` 174-185) is hit only by chance | none | checked, minor |
| D4 | `git-forms.adoc` 1-20 and the four documents | `FormsOutcomeSpecificationTest` (+132) | GDR-FORMS-1..9 stay gap-free | checked, confirms |
| D5(a) | `DataNode.java` 234-273 | **L2-3:** `DataNodeTest` builds the children of a wide mapping and checks pointers, never validates under a map-of-maps schema (the stated out-of-memory case); no bound recorded | none | checked, minor |
| D5(b) | `holdsRequired` iterative | `SchemaRequiredTest` 62-113 (55,000 flat `$defs` on the default stack) | none | checked, confirms |
| D5(c) | `NestingDepthTest.java` 302-315 | **L2-4:** `depthTimesChain` calls only `validator.required`, not `RefEvaluator` at data depth 128 under a chain of 127 | none | checked, minor |
| D5(d), (e) | `NumberBoundsTest` 95-185 | round trips at the bound, shared-subtree tests; the new API would not compile at base | none | checked, confirms |
| D5(f) | `KindConfigurationTest.java` 701-745 | **L2-5:** two aliases with the identical remote string only (see L3-8) | none | checked, minor |
| D5(g) | `SchemaValidatorPreparationTest.java` 86-106 | **L2-6:** the counter was removed, so `preparedOnce` asserts identities and can no longer notice a re-preparation inside `validate` or `required` | none | checked, minor |
| D6 | `StepUpPolicy` | `StepUpPolicyTest` 62-111, `WorkflowStepUpTest` 104 | none | checked, confirms |
| D7(a), (h) | `DocumentTransitionsReleaseRetryTest` 105-184 | UNREACHABLE, lost lease x3, REJECTED, INCOMPLETE tag scripted; `CloneReaderTest`, template and `OutputCheck` limit tests exist | none | checked, confirms |
| D7(c) | `DocumentTransitionsTest.java` | **L2-7:** `fragmentOverTheBlobLimit` asserts only `NotPerformed` and no commit; the spec says the passed-on `FAILED` | none | checked, minor |
| files | — | — | **L2-8:** `doc/concepts/git-as-document-persistence.adoc`, the removed `DocumentLayout.isOutput` and the `StoreIndex.java` change are explained by no deliverable listing; the narrative names the last only as "one accessor" | checked, minor |

**Layer 2 verdict:** findings — 8 (all minor; every deliverable traces to code and to a test that can fail). The narrative's claims are borne out: the three Sonar
suppressions (`LinkTargets.java` 56, `TransitionStep.java` 100, `DataNode.java` 286), the `cui-rewrite` marker in `UnchangedSave`, the 7(c) narrowing and the
three outliers. The operator's selection of the suppressions and the gate state are not shown by reading.

### Layer 3 — Correct

A layer-3 finding is reported to the operator at once and is never fixed inline; it becomes a staged fix-plan spec only on the operator's word.

| Defect | File:line | Triggering input | Effect | Severity | Re-read |
|--------|-----------|------------------|--------|----------|---------|
| **L3-1** | `TransitionContext.java` 197-202 | a narrative-only revision `approved -> draft -> review -> approved` (data unchanged), then a failed release v2: `document.yaml` is byte-equal to the v1 tag's file | `unreleasedTip` reads the release as complete, so the retry is `TRANSITION_REFUSED` and v2 is never released (same after `SAVED_NOT_RELEASED`); the "retry ends with the release" condition fails | **major** | re-read: the loop compares the saved file with the file at every release tag target and ends empty on a byte match; state is part of the file, so a returning state with unchanged data matches |
| **L3-2** | `TransitionSave.java` 124-127, 152-161; `TransitionRunner.java` 104-135 | a new numbered entry whose first transition is a release (custom workflow); the release fails after the allocation | the number is allocated and the ULID file deleted, but `release` returns the passed-on result without the allocated key (154-156), so the caller never learns it; a retry under the ULID reads the missing file as the initial state: `MANDATORY_FIELDS_MISSING`, or, if the caller resends all data, a second save takes the next number (duplicate entry, orphan unreleased entry). The default workflow (first transition a save) is unaffected | **major** | re-read: 124-127 builds the key from `step.allocated()`, 152-161 returns `passOn` without it; `TransitionRunner` 100-104 reads the state of the ULID key |
| not shown: L3-3 | `DataNode.java` 288-306 | map-of-maps validation at the stated size | whether the lazy pointer fixes D5(a) end to end: `getJsonPointer` still builds a full-length string per requested node, so if the validator asks for every child's pointer the cost stays at gigabytes | not shown by reading | no |
| L3-4 | `Deadlines.java` 311-313, 331-352 | `review-interval P1D` with `at` beyond about year 5.9M, or `at = Instant.MAX` as "list all" | `Math.toIntExact` and the bisection doubling raise `DateTimeException`/`ArithmeticException`, turned into "no deadline": the first occurrence is due, the document is omitted | minor | re-read: the catch at 311 returns empty; `plus` calls `toIntExact` at 351 |
| L3-5 | `Deadlines.java` 173-177 | one undecodable `document.yaml` | `due()` ends with `MALFORMED` for the whole listing, no other document's deadline is returned until it is repaired (spec-conformant, an availability hazard with no partial result) | minor | no |
| L3-6 | `StepUpPolicy.java` 64-73 | `new StepUpPolicy(true, Set.of(), Set.of(), PT5M)` | accepted by the canonical constructor; `holds()` can never be true, so every step-up transition (every release by default) is permanently `STEP_UP_REQUIRED`; the spec says `amr` defaults to `mfa` | minor | re-read: only `maxAge` is checked when enabled |
| L3-7 | `TransitionContext.java` 166-169 | a custom workflow where `approved` is reachable by a save transition; calling `approve` (from `review`) | `resumable()` checks name, release mechanism and `to == current` only, not that the state came from this transition's `from`: a document never in review is released; step-up still runs | minor | re-read: 166-169 |
| L3-8 | `KindConfiguration.java` 262-265 | two aliases spelling one repository differently (trailing `.git`, case, scheme) | the duplicate-collection check compares the remote string as registered, so the branch namespace is shared undetected | minor | no |
| L3-9 | `TransitionRunner.java` 114, 131 | a caller without step-up retries a release | the resume path fetches (network) before the step-up check, and `STEP_UP_REQUIRED` against `TRANSITION_REFUSED` tells the caller whether a release is incomplete | minor | re-read: fetch at 114, step-up at 131 |
| not shown: L3-10 | `NestingDepthTest.java` 312 | 128 data levels times a 127-schema in-place chain | `validate()` stack depth through `RefEvaluator` may overflow the default stack; an `Error` from the step is isolated as `FAILED` | not shown by reading | no |
| not shown: L3-11 | `Deadlines.java` 243-269 | clock skew between writers | the entering instant orders history by commit time (no topological sort); misplacement of the oldest commit of the run, and the cost of the walk per due document, are run-time matters | not shown by reading | no |
| not shown: L3-12 | `CatalogResolver.java` 14-19 | a throwing resolver on a changed save | only the unchanged-save path is tested (`TypedLinkCheckTest.java` 288); the changed path rests on the store's step isolation | not shown by reading | no |

**Layer 3 not shown by reading:** L3-3 (the end-to-end memory cost of the lazy pointer), L3-10 (stack depth of `validate`), L3-11 (history ordering under clock skew and
the walk's cost), L3-12 (a throwing catalog resolver on a changed save). Each needs a run.

**Layer 3 verdict:** findings — 9 (2 major, 7 minor); not shown by reading — 3.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md Conventions | Expected outcomes as results, not exceptions | conforms | results throughout; exceptions only for programming errors (`Deadlines` constructor, number below 1, enabled policy without `maxAge`) |
| AGENTS.md Conventions | JSpecify null safety | conforms | `@Nullable` on `Entry.state`, `StepUpPolicy.maxAge`, `DocumentLink.fragment`, `TransitionStep.Allocation` |
| AGENTS.md Conventions | CuiLogger with a `LogRecord` holder | conforms | `GitFormsLogMessages` is not in the diff; the new code logs nothing besides the existing GIT_FORMS-2/3 |
| AGENTS.md Conventions | Javadoc on every public type and method | conforms | the new public types and members are documented |
| AGENTS.md Conventions | No new dependency without the owner's approval | conforms | no `pom.xml` or `module-info` change in the 65 files |
| GDR-GEN-3 | Document-consistency tests: the `FormsOutcome` table matches the constants | conforms | `SAVED_NOT_RELEASED` in the enum and the `general.adoc` table; `FormsOutcomeSpecificationTest` covers it (the test checks one direction only until review finding b6b2be was fixed) |
| GDR-NFR-3 | No data value, pseudonym or claim in a detail, message or log | conforms | `TypedLink.parse` names rules, never text; `toString` redacted; `TypedLinkCheckTest` asserts no link text |
| AGENTS.md Conventions | Bounded reads | conforms | `CloneReader.read` checks 16 MiB before `getBytes`; the history walk's total cost is spec-documented, not bounded |
| PLAN-14 spec Standing Constraints | Sonar dismissal only on the operator's selection | not_shown_by_reading | three in-code `@SuppressWarnings` and one `cui-rewrite` marker exist; the operator's selection and whether the `sonar_rest` transition was used cannot be read from the diff |
| CLAUDE.md | Name the JGit version checked | conforms | Javadoc of `CloneReader` and `Deadlines` names JGit 7.8.0 |

## Metrics and Anomalies

- Tokens: 24,572,720 in total (population mixed) against a 2.5M-token budget for this plan size; finalize alone 4.1M in 5 review rounds, 3 triage loop-backs and
  6 simplify runs.
- Duration: 32,446 s wall (about 9 h).
- Anomalies: the first `branch-cleanup` hand-off (235K tokens) was refused with `merge_candidate_not_gated` because the simplify record was one commit behind the
  head; knowable before the hand-off (lesson filed as `libraries-initial-implementation-122`). Both `verify -Ppre-commit` runs and the last two full builds were
  moved to the background after 180 s despite the 600000 ms timeout (`-124`). Five-fold recurrence of the undeclared `--changed-paths-file` (`-121`). The success
  criteria that need a red run at the base commit (D5, D7) were closed without one (`-123`, L1-5).
- Effort: `unknown` in the landing facts (the runtime stamp carries none; the producer gap of `-075`).

## Routing and Merge Behavior

- Review: CodeRabbit, 5 review rounds, 56 stored findings all resolved and every review thread answered before the merge (the landing's own statement). Re-read of
  the provider at the analysis (`runbook_lane review-gate --pr-number 81`): `unresolved_review_threads: 0` (consistent with the landing's statement, which wrote
  `unknown`), `review_body_findings_total: 6` (six inline findings of CodeRabbit), `review_body_findings_handled: 0` with `merge_allowed: false`,
  `body_findings_undisposed`. The handled number is **not re-verified**: the dispositions live in the run's progress file, which this analysis cannot read, and the
  gate reads the merged pull request's present state, so a count that still shows the six as undisposed is not evidence that the landing lied (the landing wrote all three
  numbers `unknown`).
- CI/merge: merged through the merge queue as a squash; `ci pr view --pr-number 81` reads `state: merged`, `merge_commit_sha: 0187cf3…`. The squash commit carries
  CodeRabbit's "Summary by CodeRabbit" text in its body, as the landings of #76, #78 and #79 do; the queue cannot set a per-merge message.
- Sonar: the landing's `sonar_open_new_issues=0` at the merged head `5b439be`; three findings kept as in-code suppressions at the operator's selection
  (`LinkTargets` S4968, `TransitionStep` S107, `DataNode` S1643) and S1135 on the generated TODO in `UnchangedSave` fixed with the `cui-rewrite:disable` marker.
  The figure itself was not re-read here (the provider read is undecidable on a merged pull request, the producer gap `-075`); the three suppressions carry the
  operator's selection by the landing's statement only.
- Surface collisions: none observed; the gate had no in-flight candidate when PLAN-14 was emitted, and the one scope lift (`git-store`) was a decision, not a collision.
- Architecture data: `git-forms` declares no `skills_by_profile` for `implementation` and `module_testing`; it recurs for every plan touching the module (open item,
  messages 004 and 005 of the plan discarded onto it).
- Cleanup owed: `cleanup_owed=false`; `archive-plan` was `pending` at emission.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-14 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-14 --field pr --value 81`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-14 --field landing --value landings/PLAN-14.md`
- [x] row `plan_marshall_plan_id` stamped — `plan-14-forms-deadlines-links-numbers`
- [x] epic.md narrative reconciled (Open Defects: this landing's, and the PLAN-14 items closed; Queue annotations; Decisions)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated and committed with the row change
- [x] 22 `candidate-lesson` messages dispositioned and archived (`lessons-moved.md`): 13 into the plan-marshall lessons-routing inbox (`-115` to `-127`), 7 into the
  local lessons store (`2026-11-27-22-001` to `-007`), 2 discarded onto the open item "empty `skills_by_profile`"

## Follow-Ups

- **Layer 3 (operator's word needed):** L3-1 and L3-2 are majors in the resume of a failed release and in the allocation key of a release-first numbered entry; L3-4 to
  L3-9 are minors, L3-3, L3-10 to L3-12 need a run. The standing routing rule would take them into the staged `git-forms` plan that fits by content; `PLAN-27` (stages
  within a document, held back, strictly sequential after this landing) edits the same transition code, but its fold was declined once for altering GDR-FORMS-2 and -5.
  Not staged until the operator names the route.
- **Layer 2 (test gaps) L2-1 to L2-7 and the unexplained files L2-8:** the same route as layer 3; none is a defect of the shipped code by itself.
- **Layer 1:** L1-2 and L1-4 to be stated in the `git-forms.adoc` deadline section if the specification is revised (the history walk is the design of record).
- The incomplete landing (`complete: false`: `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`) is an Open Defect in
  `epic.md`; the review numbers were re-read above.
