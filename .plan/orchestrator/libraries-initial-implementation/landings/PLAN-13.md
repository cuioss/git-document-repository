# Landing Analysis: PLAN-13 — git-forms: generated output, workflow, step-up per transition

epic: libraries-initial-implementation
workstream: WS-03
pr: #64 (https://github.com/cuioss/git-document-repository/pull/64), squash-merged as `e38e079c0f9c7d5f8fae882a743c5978117ced90` on 2026-11-24T16:13Z

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-25 from the landing message
> `plan-13-forms-output-workflow-stepup-009` (the plan's inbox message), corroborated against git, the PR through the CI
> abstraction and the merged code. The message is a lead, not a fact; what follows states what was checked.

## Deliverable Fidelity vs Spec

The spec staged four deliverables; the lane's outline split them into six (`deliverables_total=6`, `deliverables_done=6`). All four
spec deliverables trace to code and to tests; the one thing the spec ruled out, a change to `git-store`, happened (see D2 and the
routing section).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Template engine decision put to the owner, recorded in `git-forms.adoc` and `general.adoc` § GDR-GEN-1 | shipped-as-specified | the owner's decision of 2026-11-23 (an own minimal template language, a strict subset of Mustache, no library) is recorded in `general.adoc` 149-154 and `git-forms.adoc`; the diff adds no pom, no `module-info.java` and no dependency. That the owner was asked BEFORE any code is not visible in a squash diff |
| 2. Generated output (GDR-FORMS-4): template, regeneration on every save attempt, CI check, kinds without a template generate and check nothing | shipped-as-specified | `DocumentTemplate` 123-170 (bounds `MAX_TEMPLATE_BYTES`, `MAX_NESTING`, `MAX_RENDER_OPERATIONS`, `MAX_OUTPUT_CHARS`), `TransitionStep` 185-192 regenerates through the store's per-attempt `SaveStep`, `OutputCheck`, `VerifyOutput`; tests `DocumentTemplateTest` 89/208/221/332/372, `DocumentTransitionsTest` 557-590 (a moved remote tip), `OutputCheckTest` 177-319, `VerifyOutputTest` 102-214 |
| 3. Workflow (GDR-FORMS-5): file, default workflow, refused transitions, `state` field, `required` on leaving the first state and on every `release` | shipped-as-specified | `Workflow.parse`/`defaultWorkflow` 138-381, `TransitionStep` 196-200, `StateMerge` 66; tests `WorkflowTest` 91-269, `DocumentTransitionsTest` 372-535, `StateMergeTest` 37-70. Deadlines are parsed and not evaluated, as GDR-FORMS-6 is PLAN-14's |
| 4. Step-up (GDR-FORMS-7): per transition with the defaults, `intermediate-save` refused at load, `step-up.amr` / `acr` / `max-age` | shipped-as-specified | `Workflow` 208-217, `StepUpPolicy` 87-96; tests `StepUpPolicyTest` 61-168 (one per row), `WorkflowStepUpTest` 50-104, `DocumentTransitionsTest` 615-672 (the deviating example of the spec) |
| (spec constraint) no change to `git-store`; no change to `.github/project.yml` | **shipped-modified** | `git-store` changed once: `StoreWriter.release(author, claims, key, expectedCommitId)` (GDR-STORE-6) with `DocumentRelease` and `StoreClone`, and `ReleaseTest` (185 added lines). The operator approved that single change during review, and the PR body was corrected. `.github/project.yml` is not in the diff |

Added-unplanned, not in the spec: the S6539 split of `DocumentTransitions` into a facade over seven package-private classes
(`TransitionRunner`, `TransitionContext`, `TransitionSave`, `DraftStaging`, `UnchangedSave`, `TransitionCall`,
`TransitionOutcomes`, beside `TransitionStep`, `StateMerge`, `CloneReader`), and the review fixes of fix tasks 14 to 27.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`) at `e38e079`,
by reading only, with no build, no test run and no write. The orchestrator re-read the `checked` points at their `file:line` before
writing them here: `TransitionSave` 120-140, `TransitionRunner` 85-105, `Workflow` 365-382, `TransitionContext` 115-140,
`StepUpPolicy` 85-97, `DocumentRelease` 198-210, 255-300, 455-472 and 520-536, `StoreWriter` 332-352, `DocumentTemplate` 258-275
and 305-320, `OutputCheck` 380-388, `DocumentLayout` 275-305, `DataNode` 120-145, `VerifyOutput` 180-190, `ExplicitSave` 900-908 and the
callers of `isOutput`. One chain was NOT re-read end to end: the throw of the depth-129 part through `OutputCheck` and
`DocumentTemplate` (L3-6); both ends were re-read, the middle is the leaf's.

- **Dispatch:** one read-only leaf per landing — reason: reading about 45 changed files of merged code on three layers is the
  depth the dispatch rule exists for, and the read must not share context with the PLAN-17 landing analysed the same day.
- **Verdict:** layer 1 findings (2, minor); layer 2 findings (2, minor; no unexplained code file); layer 3 findings (**1 major**,
  5 minor, 7 info).

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | the premise "no change to `git-store`" could not hold: a release that publishes exactly what a save wrote needs a pinned release | checked | refutes | `DocumentRelease.java` 201-208 and 281-300, `StoreWriter.java` 332-352 |
| L1-2 | the spec is silent on how a release that failed after its save is retried, and on how far in the future an `auth_time` may lie | checked | refutes | `git-forms.adoc` 714-750; `StepUpPolicy.java` 95 |
| L1-3 | the hypothesis that the store's save offers a per-attempt hook | checked | confirms | `ExplicitSave.java` 375, 397-411; `TransitionStep.java` 96-99 |
| L1-4 | the Expected Surface over-lists `pom.xml` (no pom changed) and under-lists the `git-store` main files, `ReleaseTest`, `git-store.adoc`, `module-structure.adoc`, `schema-driven-documents.adoc` and ADR 0008 | checked | refutes | `git diff 7531b72 e38e079 --stat` |

L1-3 confirms a claim that already carries `corroborated` (stamped at `ad9e773`); an equal-strength finding persists nothing.

**Layer 1 verdict:** findings — 3 (L1-1, L1-2, L1-4), none blocking.

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 template decision | `general.adoc` 149-154, `DocumentTemplate.java` 36 | `DocumentTemplateTest` | none; "put to the owner before any code" not visible in a squash | checked |
| 2 generated output | `DocumentTemplate` 123-170, `TransitionStep` 185-192, `OutputCheck` 135-137 | `DocumentTransitionsTest` 557-590, `OutputCheckTest`, `VerifyOutputTest` | L2-1: no test puts stale or foreign content at the output path and shows the save replaces it ("never merged"; only the unchanged self-loop at 830) | checked |
| 3 workflow | `Workflow` 138-381, `TransitionStep` 196-200, `StateMerge` 66 | `WorkflowTest`, `StateMergeTest`, `DocumentTransitionsTest` | none | checked |
| 4 step-up | `Workflow` 208-217, `StepUpPolicy` 87-96 | `StepUpPolicyTest`, `WorkflowStepUpTest` | none | checked |
| (review fixes) | `TransitionSave` 129-135 | `DocumentTransitionsTest` 702-724 | L2-2: `failedReleaseKeepsTheSavedState` asserts only that the state stays after a failed release; no test retries the transition, which is why L3-1 went unnoticed | checked |

Files the diff touched that no deliverable explains (all documentation or the approved `git-store` change): `doc/specification/git-store.adoc`,
`doc/concepts/module-structure.adoc`, `doc/concepts/schema-driven-documents.adoc`, ADR 0008, `git-store` main and `ReleaseTest`.

**Layer 2 verdict:** findings — 2 (L2-1, L2-2), none blocking.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `TransitionSave.java` 129-135, `TransitionContext.java` 128-131, `Workflow.java` 372-381, `TransitionRunner.java` 93-96 | a `release` transition (approve) whose push of `main` fails after the state save: `UNREACHABLE`, a lease lost on every attempt, `REJECTED`, an `INCOMPLETE` tag push, signing `REFUSED`, the pin `CONFLICT` | the branch says `approved`, `main` and the tag do not; the retry reads `state=approved`, finds no transition from it and returns `TRANSITION_REFUSED`; recovery is a direct `StoreWriter.release` or a release self-loop (only `selfLoopWorkflow`, `DocumentTransitionsTest` 727, recovers) | **major** |
| L3-2 | `DocumentRelease.java` 259-265, 458-470, 523-535 | release A leaves `main` merged with its tag missing (`INCOMPLETE`); the user saves B; a pinned release of B runs | the tag goes onto A's release commit and an `OK` `Release` names A while B is unreleased; `TransitionSave` 130-135 reports success; the Javadoc says the path "publishes nothing new" | minor (conformance: GDR-STORE-6 deviates) |
| L3-3 | `DocumentTemplate.java` 268-273 | a template with `{{>name}}` inside a section over a long data list and a large whitespace-only fragment (up to 16 MiB) | a fragment node costs one operation but copies and scans the whole fragment; the output limit never trips; up to 1,000,000 scans of 16 MiB in one save attempt under the store's locks or in the CI check (the cost in seconds was not run) | minor |
| L3-4 | `ExplicitSave.java` 905-906 against `OutputCheck.java` 384-386 | a fragment, part or document file over 16 MiB (`MAX_FILE_BYTES`) that a save commits | the save reads it whole (`getBytes(Integer.MAX_VALUE)`, also all `narrative/*.adoc` the template never references) and commits; `OutputCheck` and `VerifyOutput` then return `MALFORMED` for the whole commit | minor |
| L3-5 | `StepUpPolicy.java` 95 | `IdentityClaims` with an `auth_time` years in the future | `Duration.between` is negative, `compareTo(maxAge) <= 0` holds forever: step-up is satisfied by a claim that never ages. Deliberate and documented (spec text, `StepUpPolicyTest` 148); the validity of the claims is the caller's duty | minor (kept) |
| L3-6 | `DocumentLayout.java` 279-302, `DataNode.java` 126-142, `OutputCheck.java` 269-279, `VerifyOutput.java` 184-188, `ExplicitSave.java` 404-409 | a split part file nested at the 128-level limit, which `codec.part` accepts alone | `join` puts it under a top-level key, depth 129; `requireDataModel` throws `IllegalArgumentException`; `OutputCheck.check` throws it (undeclared, against "expected outcomes are results"), `VerifyOutput` prints `NOT_A_REPOSITORY git-dir: not a repository`, a save ends `FAILED`; no test at depth 129 | minor |
| info | `StateMerge.java` 60-64 | a draft that changes one nested field, a concurrent tip change to a sibling nested field | the merge is per top-level key, so the sibling change is lost; by design, `DocumentTransitionsTest` 921 pins "draft wins" | info |
| info | `Workflow.java` 275-280 | a 1 MiB workflow with tens of thousands of states | `List.contains` per key: quadratic at load; the file is configuration, not user input | info |
| info | `VerifyOutput.java` 184 | a path with a newline or control character in a tree entry | printed unescaped, one per line, so a CI consumer's lines can split | info |
| info | `VerifyOutput.java` 205-238 | a parent directory swapped for a link between the prefix check and the open | `NOFOLLOW_LINKS` covers the last component only; a race window; the `git-dir` argument is not link-checked | info |
| info | `DocumentLayout.java` 159-196 | a caller that saves without the step | `isOutput` ("the one file never merged") has no production caller (tests only); "never merged" holds only through `TransitionStep`'s put of the output path | info |
| info | `Workflow.java` 168-170 | two transitions with one name from different states | refused at load, which the spec text does not say | info |
| info | `TransitionRunner.java` 99-102 | — | step-up is checked before any write and the state is re-checked on the remote tip on every attempt: confirmed, `DocumentTransitionsTest` 615-640 | info (confirms) |

**Layer 3 not shown by reading:** L3-3's cost in seconds (the scan was not run); the number of open Sonar findings at the head; the
concurrency of two `perform` calls for one author and key on two threads (the store's locks were not traced); whether the
`DomainTermAbsenceTest` accepts the new public names `Workflow`, `Deadline`, `Transition`, `Mechanism`; whether any test would fail on a
regression (the assertions were read, not mutated).

**Layer 3 verdict:** findings — 6 defects (1 major, 5 minor) and 7 info; none a blocker; not shown by reading — 5.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | expected outcomes are returned as results, not thrown | **deviates** | `OutputCheck.check` can throw for a part at depth 129 (L3-6); a key outside the kind's collection throws `IllegalArgumentException` (`TransitionContext.java` 83-91, documented as a programming error) |
| AGENTS.md | JSpecify, `@NullMarked`, `@Nullable` | conforms | `OutputCheck` 76, `TransitionStep` 68, `StateMerge` 53 |
| AGENTS.md | `CuiLogger` with a `LogRecord` holder, catalogued | conforms | `TransitionRunner` 42, 108-111; `GitFormsLogMessages` 57-77; `LogMessages.adoc` gains `GIT_FORMS-2` and `-3`; `TransitionSecretsTest` 90, 145 |
| AGENTS.md | Javadoc on every public type and method | conforms | public types read, package-private ones also documented |
| AGENTS.md | secrets never in results, exceptions, logs, commit metadata (GDR-NFR-3) | conforms | `StepUpPolicy.holds` logs nothing and returns no claim (87-96); details are paths, lines, limits or outcome names |
| AGENTS.md | no dependency without approval, none in lower modules | conforms | no pom or `module-info.java` in the diff stat |
| AGENTS.md | tests JUnit 5, `cui-test-generator`, EasyMock | conforms (partly not checked) | the only double is a fake `SigningKeySource` with a real Ed25519 key (`DocumentTransitionsTest` 1028); the use of `cui-test-generator` was not checked |
| AGENTS.md | each GDR-FORMS requirement covered by a test naming its id | conforms | `@DisplayName`s name GDR-FORMS-4, -5 and -7 |
| `git-forms.adoc` | GDR-FORMS-4/5/7 | conforms | each bullet maps to code; the gaps are L3-1 and the edge cases |
| `git-store.adoc` | GDR-STORE-6: a pinned release publishes exactly the saved commit and is refused with nothing written when the branch moved | **deviates** | the write path conforms (`DocumentRelease` 281-300); the tag-only recovery path does not (L3-2) |
| plan spec | no change to `git-store`; none to `.github/project.yml` | **deviates** | `git-store` changed with the operator's approval during review (L1-1); `project.yml` untouched |

## Metrics and Anomalies

- Tokens: 27,433,649 in total over every session (landing fact); the retrospective's per-phase figures are in the plan, not re-read here.
- Duration: 164,741 s of wall clock (about 45.8 hours) over 27 tasks, of which tasks 14 to 27 were review and Sonar fix tasks.
- Anomalies: `effort=unknown` in the landing facts (the one missing required key); the plan ran in the light lane, escalated to the deep
  lane when the 45-file surface exceeded the cap of 25, and kept a stale `phase_exemptions.2-refine` (a plan-marshall defect, moved);
  the `scope_creep_check` blocked on the plan's own fix tasks (moved); the base moved during review (the `git-store` anchoring plan
  landed) and the branch was rebased with two documentation conflicts and force-pushed with a lease.

## Routing and Merge Behavior

- Review: CodeRabbit (required) reviewed and all 12 of its comments were fixed and answered (three loop-backs: a lost update, a release
  not pinned to the commit it was checked against, exponential number rendering, missing size bounds). Sourcery (optional) refused the
  diff on size (over 150,000 characters). The barrier was passed under an operator-recorded authorization over that review gap. The three
  review numbers were re-read at 2026-11-25 with `runbook_lane review-gate --pr-number 64`: the read is **undecidable** (`undecidable_read`:
  the parser could not read the CodeRabbit body section "Minor comments: 1"), so `unresolved_review_threads=0`,
  `review_body_findings_total=2` and `review_body_findings_handled=2` are recorded as **not re-verified**, not as a difference.
- CI/merge: checks green (`mergeStateStatus` CLEAN at the last read), squash-merged through the queue as `e38e079`. The rebase conflicted in two
  documents; the declared surface did not predict `doc/concepts/schema-driven-documents.adoc`, `doc/specification/git-store.adoc` and
  the `git-store` main files (L1-4). The under-declaring spec is the spent PLAN-13, so no declaration is corrected; the consequence is for
  PLAN-22, which edits the same `git-store` files (`DocumentRelease`, `StoreWriter`) and must rebase over the pinned release (its PR #76 is
  open): recorded as a Watch.
- Sonar: the landing says `sonar_open_new_issues=0` and the PR body says 0 open new-code issues and no hotspots at the final head. This
  analysis did not re-measure it: the Sonar project key is not configured locally. Recorded as the lane's claim, not as measured.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-13 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-13 --field pr --value 64`
- [x] row `landing` stamped — `landings/PLAN-13.md`
- [x] row `plan_marshall_plan_id` already stamped (`plan-13-forms-output-workflow-stepup`)
- [x] `epic.md` narrative reconciled: queue annotation, Decision, Open Defects (incomplete landing; L3-1, L3-3 to L3-6 routed to PLAN-14 d7; L3-2 routed to PLAN-25), Watch (PLAN-22 rebase over the pinned release)
- [x] resume anchor updated; `queue-view.md` regenerated
- [x] eight candidate lessons moved to the plan-marshall `lessons-routing` inbox (`libraries-initial-implementation-080` to `-087`), recorded in `lessons-moved.md`

## Follow-Ups

- L3-1 (major, resume of a failed release), L3-3 (fragment cost), L3-4 (input bound of the save against the check), L3-6 (depth 129), L2-1,
  L2-2, the info items on `isOutput`, the unescaped path and the duplicate transition name, and the recorded decision on L3-5 went into **PLAN-14
  deliverable 7**, with the operator's answer "resume" for L3-1 (2026-11-24). No new file surface: every file lies under `git-forms/` and
  `doc/specification/git-forms.adoc`.
- L3-2 (the pinned release on the tag-only recovery path) went into the new container plan **PLAN-25** (`git-store`, held back from `next`),
  on the operator's answer of 2026-11-24.
- L1-1, L1-2 and L1-4 (the spec premise and the surface over- and under-declared) are recorded here; PLAN-13's spec is spent.
- The landing is incomplete (`effort=unknown`): an Open Defect in `epic.md`; the manual paste of that plan may surface the effort.
