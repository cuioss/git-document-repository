# Landing Analysis: PLAN-06 — Build guards: module layering, git-core-only consumer check, native-image metadata

epic: libraries-initial-implementation
workstream: WS-01
pr: #39 (https://github.com/cuioss/git-document-repository/pull/39), merged through the merge queue as `e43c7e9` (squash, parent `364dc50`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `implement-plan-06-build-guards-014`
> after verifying it against git, the CI abstraction, the Sonar read and the merged build configuration. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git show --stat e43c7e9`, 15 paths, +615/-19: the root `pom.xml` (+37), `git-core/pom.xml` (+39) and `git-worktrees/pom.xml` (+20), the `git-core` native-image metadata
(`META-INF/native-image/de.cuioss/git-core/reflect-config.json`, `resource-config.json`), the tests `DomainTermAbsenceTest` in `git-core`, `git-testing` and `git-worktrees`,
`NativeImageMetadataTest` (`git-core`) and a one-test change in `JGitRepositoryOperationsTest`, and the documents `AGENTS.md`, `README.adoc`, `doc/README.adoc`, `doc/concepts/module-structure.adoc`
and `doc/specification/general.adoc` (+69). No main Java file, no `module-info.java`, no `.github` path is touched, and every `pom.xml` hunk adds `maven-enforcer-plugin` executions only: no `<dependency>`, no
plugin `<dependencies>`. Of the spec's Expected Surface every touched entry is declared; declared but not touched is `git-worktrees/src/main/resources/` (the dropped metadata). Undeclared: `doc/concepts/module-structure.adoc`
(a three-line status change, named by the Standing Constraints), the three `DomainTermAbsenceTest` files (the second half of deliverable 2), `NativeImageMetadataTest` (a guard for deliverable 3) and `JGitRepositoryOperationsTest` (a flake fix the PR body names).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Layering guard (GDR-GEN-1) | shipped-as-specified (self-policing, L3-2) | root `pom.xml` 55-91: execution `module-layering`, the built-in `bannedDependencies` rule with `searchTransitive=false`, excluding `de.cuioss:git-*` and including `de.cuioss:git-testing:*:*:test`; `git-worktrees/pom.xml` 71-90 appends `de.cuioss:git-core` with `combine.children="append"`; the choice (Enforcer rule, not an architecture test, which would need a new dependency) is recorded in `general.adoc` in place of "is open" |
| 2. Consumer check | shipped-modified (an Enforcer execution inside `git-core`, not "a test consumer project"; recorded as the owner's decision) | `git-core/pom.xml` 75-115: execution `consumer-isolation`, `bannedDependencies` with `searchTransitive=true`, excluding every compile and runtime artifact and including JSpecify, JGit, JavaEWAH, SLF4J API, Commons Codec and `cui-java-tools`; the domain-term check is `DomainTermAbsenceTest` in each of the three modules (scan of the module's main output, public types by simple and package name against `assessment`, `threat`, `incident`, `risk`) |
| 3. Native-image metadata (GDR-NFR-4) | shipped-modified (`git-core` only; `git-worktrees` ships none, the operator's decision after research) | `META-INF/native-image/de.cuioss/git-core/`: `reflect-config.json` (an array with `org.eclipse.jgit.internal.JGitText`, `allPublicFields`, a no-argument `<init>`) and `resource-config.json` (an object with the bundle `org.eclipse.jgit.internal.JGitText`); `NativeImageMetadataTest`; the documents say `git-worktrees` relies on `git-core`'s metadata |
| 4. Release readiness | shipped-as-specified | `README.adoc` and `AGENTS.md` describe the filled foundation, `project.yml` is untouched, the "first verification is `pm-mcp-core`'s native build" sentence is kept (`general.adoc` 294-296) |

The lane reports 8 outline deliverables and 7 done; the spec carries 4 — the outline split them per module (lessons `2026-11-20-07-002`) and the lane's dropped deliverable 7 is the `git-worktrees` metadata. The two departures are documented consistently:
`general.adoc` 284-292, `AGENTS.md` 56-57, `README.adoc` 52-53 and `doc/README.adoc` 126-127 all say `git-worktrees` ships none and relies on `git-core`'s, and none implies a `git-worktrees` folder. The stated reason holds by reading:
the main sources of `git-core` and `git-worktrees` contain no reflection, `getResource` or `ServiceLoader`. That `solution_outline.md` still lists deliverable 7 is a plan artefact outside the repository and is not shown by reading (lesson `2026-11-20-07-005`).

Claims of the landing against the diff: the 15 paths and +615/-19, the pom sizes, deliverable 7 absent, the consumer check as an Enforcer execution, the two hand-duplicated lists (the domain terms in three tests, the `git-worktrees` include repeating the declared dependency edge) and the squash message carrying
the PR body are borne out. **Not shown by reading:** the unamended outline, the Sourcery quota refusal, the 15.1M tokens and the doubled outline and plan phases (plan facts).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `e43c7e9`, the parent-pom and the JGit jar, and the Enforcer 3.6.3 bytecode. By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: reading merged build configuration against the spec is the depth the dispatch rule exists for, and one landing's read must not share context with the reconciliation of the 13 lessons drained in the same scan.
- **Verdict:** layer 1 — 4 of 4 claims corroborated, 2 deliberate departures, 1 minor; layer 2 — 3 minor, 1 not shown by reading; layer 3 — no blocker, no major, 3 minor re-read, 1 minor lead, 4 not shown by reading.
- **Re-read by the orchestrator at `e43c7e9`:** root `pom.xml` 50-92, `git-worktrees/pom.xml` 71-91, `git-core/pom.xml` 75-116; `reflect-config.json`, `resource-config.json`; `NativeImageMetadataTest.java` 50-109; `git-core` `DomainTermAbsenceTest.java` 36-99; `JGitRepositoryOperationsTest.java` 1473-1481; the base `pom.xml` and `general.adoc` (`364dc50`); `general.adoc` 284-296 and `README.adoc` 50-54 at the head; the squash message's Test Plan; a search of `git-core` main for `DfsText` and `storage.dfs` (none); `cui-parent-pom` 1.7.7 (the enforcer plugin at 205-209, 378, 636); the JGit 7.8.0 jar listing (no `META-INF/native-image`, no `META-INF/services`); and the `javap` bytecode of `ResolverUtil` and `BannedDependenciesBase` in `enforcer-rules-3.6.3.jar`. **Not re-read** (leaf-only, recorded as unverified leads): the `bannedDependencies` match logic of `BannedDependencies` for `searchTransitive=false` (the leaf's bytecode reading, L3-3), `combine.children="append"` behaviour, the JGit `TranslationBundle` / `GlobalBundleCache` reads behind the metadata entry, the `GitCredentialGenerator` ranges behind the seed fix, and the per-module copies of `DomainTermAbsenceTest` beyond `git-core`'s.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-0 | Claim 0: the base root `pom.xml` lists three modules and has no enforcer configuration | checked | confirms | `git show 364dc50:pom.xml`: three `<module>` lines, no match for `enforcer` |
| L1-1 | Claim 1: the base says the build rule "is open" and that GDR-NFR-4 is not verified | checked | confirms | base `general.adoc` 30; after the PR only the NFR-4 sentence remains (294-296) |
| L1-2 | Claim 2: `cui-java-parent` 1.7.7 already configures `maven-enforcer-plugin` with rules a reactor rule could join | checked | confirms | one level up: the plugin is in the ancestor `cui-parent-pom` 1.7.7 (managed at 3.6.3, execution `mojo-enforcer-rules`, `extra-enforcer-rules`), not in `cui-java-parent` or `cui-java-bom`; the PR added separate executions instead of joining the inherited list; the PR body's wording is imprecise, `general.adoc` words it correctly |
| L1-3 | Claim 3: JGit 7.8.0 ships no GraalVM metadata | checked | confirms | the 7.8.0 jar lists no `META-INF/native-image` and no `META-INF/services` entry |
| L1-4 | Deliverable 2 as worded asks for a test consumer project; the delivery is an Enforcer execution inside `git-core` | checked | confirms | recorded as the owner's decision (`general.adoc` 51); the whitelist is stricter than "no third-party artifact of the upper modules", but it runs on `git-core`'s own resolution and approximates a consumer's. Minor |
| L1-5 | The Done-when "the layering guard fails a deliberately wrong reactor dependency (a test or a recorded verification run)" | not shown by reading | none | the diff holds no test and no recorded run; the two negative runs are only in the squash message's Test Plan (re-read), citing the plan's work log (L2-1) |
| L1-6 | The Done-when items "`verify` passes at the head" and the Sonar read | not shown by reading | none | the Sonar gate is settled below; the squash message leaves "`build / conclusion` green" unticked |
| L1-7 | "Each later module plan adds one line" | checked | confirms | partly: a later module adds a plugin block of about 20 lines to its POM, of which one line is the include; `AGENTS.md` words it "one include per edge". Info |

Persisted onto the spec (`corpus set-verdict`): claims 0-3 corroborated.

**Layer 1 verdict:** findings — 1 minor (L1-4), 2 info; 2 not shown by reading (L1-5, L1-6)

### Layer 2 — Implemented

Each deliverable traces to configuration or code that can fail: `module-layering` fails on any `de.cuioss:git-*` dependency not covered by an include, in any scope (the include for `git-testing` covers scope `test` only), `consumer-isolation` fails on a new compile or runtime artifact outside its allow-list, `DomainTermAbsenceTest` fails on a public type or package naming a term and on an empty scan, `NativeImageMetadataTest` fails on a renamed class or bundle. Layer-2 findings:

- **L2-1 (minor).** The guards have no regression test in the repository: the two negative runs (`git-testing` at scope `compile` in `git-worktrees`; `cui-test-generator` at scope `compile` in `git-core`) are recorded only in the squash message (re-read).
- **L2-2 (minor).** `README.adoc` says `git-core` "ships the GraalVM native-image metadata for the JGit classes it reaches" and `general.adoc` says "the one translation class the code paths of `git-core` reach is `JGitText`"; the delivery lists one class and one bundle. Whether a path reaches `DfsText` (the in-memory DFS backend that `ReferenceUpdater` handles) was not traced.
- **L2-3 (minor).** The documents call GDR-GEN-1 "implemented" although its "once all planned modules exist" and "no public type of any module" parts are conditional on modules that do not exist yet, and the consumer check cannot fail on an upper-module artifact today (the shape-only caveat in the spec). A mild overclaim.
- **L2-4 (note).** `JGitRepositoryOperationsTest.refusedRedirectLogged` is a seed-dependent test fix the narrative names: the generator draws a user name of one or more letters, which collided by chance with the captured log text; the fix prefixes `redirect-user-` (re-read). Correct, and the third plan in a row to fix a flake of this kind (a candidate for PLAN-20).
- **L2-5 (not shown by reading).** `combine.children="append"` in a same-id execution appends to the inherited include list rather than replacing it (indirect evidence only: `git-worktrees` declares a compile dependency on `git-core`, so a replaced list would fail its own validate phase and the PR ticks `verify`).

**Layer 2 verdict:** findings — 3 minor (L2-1 to L2-3), 1 note (L2-4); 1 not shown by reading (L2-5)

### Layer 3 — Correct

No blocker and no major defect. The configuration is careful in the large: the layering rule bans every direct `de.cuioss:git-*` dependency in every scope except the one it includes, the consumer rule is a whitelist over the transitive compile and runtime closure with the owner's gate in its message, `DomainTermAbsenceTest` loads classes without initialising them and fails loudly on an empty scan, and the metadata is one class with `allPublicFields` plus the no-argument constructor `TranslationBundle` needs. The defects are at the edges. Re-read by the orchestrator at `e43c7e9`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `git-core/pom.xml:95-109` (`consumer-isolation`, `searchTransitive=true`); Enforcer 3.6.3 `ResolverUtil.resolveTransitiveDependenciesVerbose` → `resolveTransitiveDependencies(true, false, true, …)` whose third flag filters `Dependency.isOptional()` (bytecode re-read) | a third-party library added as an `<optional>true</optional>` compile dependency of `git-core` | the build stays green with no allow-list edit and no owner approval, contradicting `AGENTS.md` 91-92 ("until then the build fails"); a consumer does not receive an optional dependency by default, so the GDR-GEN-1 invariant itself holds, only the approval gate has a hole. The lane's review-body note ("optional dependencies bypassing `consumer-isolation`", unverified) is confirmed | minor |
| L3-2 | root `pom.xml:55-91` and `git-worktrees/pom.xml:71-90` (re-read) | a PR adds an include or sets `combine.self="override"` in one module's POM | the guard is self-policing: a module widens its own allowed edges, nothing ties the includes to the graph in `module-structure.adoc` (hand-copied, the CodeRabbit point) and a module POM can override the inherited rule or its phase; only review catches it | minor |
| L3-3 | `DomainTermAbsenceTest.java:45` in three modules (re-read for `git-core`) | a term added to one list | three byte-for-byte parallel copies drift: the term does not reach the other two; neither `general.adoc` nor `AGENTS.md` records the accepted trade-off the lane gave to the review | minor |
| L3-4 | `NativeImageMetadataTest.java:58-59, 101-108` (re-read) | `resource-config.json` edited into a JSON array that still names the bundle | the regular-expression extraction parses no JSON and checks no shape, so the test passes and a native build fails (the array-versus-object mistake the lane itself made); a removed entry is caught only when the list becomes empty | minor |
| L3-5 (lead) | root `pom.xml:75-84` | a dependency in plugin `<dependencies>`, an `annotationProcessorPaths` entry or an inactive profile; `-Denforcer.skip` | `bannedDependencies` reads the active model's dependencies only, so these bypass it; whether CI passes skip flags is not shown (the reusable workflow was not read) | not shown by reading |
| L3-6 (not shown) | `reflect-config.json:1-12`, `resource-config.json:1-7` | a native build of a consumer | whether other JGit paths need metadata or initialisation arguments (no `native-image.properties` is shipped), whether `DfsText` is reached, whether the legacy JSON forms are read by the target GraalVM; a missing entry fails at image-build or run time, not here | not shown by reading |
| L3-7 (info) | `git-core/pom.xml:83-109` | a consumer's own `dependencyManagement` | the rule evaluates `git-core`'s own graph under its own management, so a consumer's resolution could differ in scope or version; the allow-list is version-agnostic, so unlikely to matter; a JGit upgrade that adds a compile dependency fails the build with a message that blames `git-core`'s own addition | info |
| L3-8 (info) | `JGitRepositoryOperationsTest.java:1473-1504` | other tests with a short generated value inside a `contains` absence assertion | the seed fix is sound and the token needs none; a search found no other `contains(…username())` assertion; the others were not exhaustively checked | info |

**Layer 3 not shown by reading:** native-image completeness (L3-6), CI skip flags (L3-5), the in-reactor behaviour of the rules at the `validate` phase and the `verify` result on Java 21, 25 and 27 (the project's CI matrix lists 27).

**Layer 3 verdict:** findings — 4 minor (L3-1 to L3-4); 2 not shown by reading (L3-5, L3-6); 2 info

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md / CLAUDE.md | No dependency without the owner's approval | conforms | no new `<dependency>` and no plugin `<dependencies>` in any hunk; the plugin (3.6.3) and `extra-enforcer-rules` come from `cui-parent-pom` 1.7.7, the new blocks carry no version, the rule is the built-in `bannedDependencies`; the architecture-test alternative was rejected because it needs a new dependency (`general.adoc` 35-36) |
| CLAUDE.md | Never touch `.github/project.yml` | conforms | not among the 15 paths |
| AGENTS.md | Javadoc on every public type and method | conforms | only package-private test classes were added, with class-level Javadoc |
| AGENTS.md | License header on every source file | conforms | headers read on the four new Java files |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | no main Java file or package was added |
| AGENTS.md | Tests: JUnit 5, `cui-test-generator`, EasyMock for doubles | conforms | the new tests need no generated data or doubles; the edited test keeps `GitGenerators` |
| `general.adoc` GDR-GEN-3 / GDR-GEN-4 | Results, `CuiLogger` | conforms | no operation or logging code changed |
| AGENTS.md | Third-party dependencies stay out of the lower modules | conforms, with one blind spot | `consumer-isolation` is a whitelist that fails on a new compile or runtime artifact; a direct optional dependency is the hole (L3-1) |
| CLAUDE.md | Verify JGit claims and name the version | conforms | `general.adoc` names 7.8.0; the `TranslationBundle` and `GlobalBundleCache` claims behind the metadata entry were read by the leaf |
| AGENTS.md | Every statement about an external product cites its source | not shown by reading | the GraalVM statements are linked; "GraalVM recommends no empty placeholder file" (`general.adoc` 288) is a negative claim without a specific citation and the GraalVM pages were not fetched |

## Metrics and Anomalies

- Tokens: 15,097,618 (the lane's figure, not re-verified; the retrospective's budget reference is 2.5M per plan). Duration: 37,608 s (about 10.4 h).
- Anomalies: `effort` is `medium` (self-reported); the three review numbers are `unknown`, so `inbox landing-check` reports `complete: false` (missing `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`); `sonar_open_new_issues` is `0` (the lane's own number). The outline and plan phases ran twice after the operator chose to redo them, and execute was re-entered for the seed-dependent test fix. An infeasible leaf return (the invalid `resource-config.json`) was recorded as `termination_cause: error` (195,152 tokens, lesson `2026-11-18-13-009`). Dropping deliverable 7 left the outline listing it (`2026-11-20-07-005`). The outline's findings: a substituted artifact without a recorded deviation (`2026-11-20-07-001`), deliverables whose module did not match their files, found twice (`-002`), an unrecorded question and an unchecked format decision (`-003`), `architecture which-module` returning null for resource paths (`-004`). `manage-change-ledger append --changed-paths-file` failed twice, the seventh plan (`2026-11-18-13-004`); `qgate list` was called without `--phase` (`2026-11-18-07-002`).

## Routing and Merge Behavior

- Review: the lane accepted the two CodeRabbit inline comments (the domain terms and the module-edge include copied by hand) as trade-offs and took the review-body note into account without verifying it; Sourcery (optional) refused for quota. **Provider re-read:** `review-gate` for #39 returns `unresolved_review_threads: 0`, `review_body_findings_total: 3`, `review_body_findings_handled: 0`, `merge_allowed: false`, `body_findings_undisposed` (CodeRabbit). The landing's three review numbers are `unknown`, so there is no difference to record as a false claim; `handled: 0` counts dispositions the provider does not carry, so `review_body_findings_handled` is **not re-verified**. The review-body note the lane could not verify is confirmed here (L3-1).
- CI/merge: merged through the merge queue as a squash, `e43c7e9`; the CI abstraction reads state `merged`. The squash message carries the PR body and a CodeRabbit summary; the Test Plan leaves "`build / conclusion` green" unticked.
- Sonar: the landing records `sonar_open_new_issues=0`. **Re-read:** the quality gate of #39 is `OK` (five conditions, no coverage condition: the PR adds no main code; hotspots 100 % reviewed); the gate is a verdict, not a count of open issues, so the count is not re-verified.
- Overlap: PLAN-06 ran alone (PLAN-05 had shipped). It touched the root `pom.xml`, `AGENTS.md`, `README.adoc`, `doc/README.adoc` and `general.adoc`, which the staged PLAN-20 and PLAN-07 also declare in part; PLAN-20 was updated in the same act (see the reconciliation).

## Reconciliation Actions

- [x] row `status` -> `shipped` — `orchestrator queue --transition PLAN-06 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-06 --field pr --value 39`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-06 --field landing --value landings/PLAN-06.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-06 --field plan_marshall_plan_id --value implement-plan-06-build-guards`
- [x] epic.md narrative reconciled (PLAN-06 shipped; the lesson dispositions; the layer-3 report and PLAN-20's deliverable 6; the incomplete landings)
- [x] layer-1 verdicts persisted onto the spec (claims 0-3 corroborated)
- [x] PLAN-20 updated in the same act: deliverable 6 (the optional-dependency hole, the unrecorded negative runs, the accepted trade-offs, the metadata test shape, the native-image wording), the surface gained the root `pom.xml`, `git-core/pom.xml`, the native-image directory and `README.adoc`, four claims were added (three stamped corroborated), a `git-testing` item for the recurring generated-value flake, the Done-when base is now `e43c7e9`
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-ups

- The layer-3 defects L3-1 to L3-4 and the layer-2 residue are folded into PLAN-20 (deliverable 6); no new plan is staged. PLAN-20 now has six deliverables, at the split guard: it proceeds unsplit because the operator asked for one collection plan and each deliverable is independent, and the operator may split it when releasing it.
- PLAN-06 is the last foundation plan. The 0.1.0 release is an operator act through `/release`, not a queue row; PLAN-07 (the first module-creating plan) follows it.
