# Landing Analysis: PLAN-07 — git-store: module, repository registry, configuration, codecs and service interfaces

epic: libraries-initial-implementation
workstream: WS-02
pr: #41 (https://github.com/cuioss/git-document-repository/pull/41), merged through the merge queue as `9bdc5ba` (squash, parent `3c609a7`); a follow-up #43 (`a558afd`, the operator's, after the landing) corrects the justification comment of the S4449 suppressions in `StoreConfiguration.java`

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-07-store-module-registry-spi-011`
> after verifying it against git, the CI abstraction and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git show --stat 9bdc5ba` without the ledger paths, 41 paths, +5091/-58. New: the module `git-store` (`pom.xml`, `module-info.java`, 15 main types in `de.cuioss.git.document.store`, `package-info.java`, `doc/LogMessages.adoc`, 13 test types and three `META-INF/services` test registrations). Changed:
`pom.xml` (+1, the module), `AGENTS.md` (+25/-), `README.adoc`, `doc/README.adoc`, `doc/concepts/module-structure.adoc`, the module dependency graph SVG, `doc/specification/general.adoc` (+91) and `doc/specification/git-store.adoc` (+160). No `git-core` or `git-worktrees` file, no `.github` path is touched. The range `e43c7e9..9bdc5ba` also holds #40 (the ledger) and #42 (the `AGENTS.md` merge authorisation); neither belongs to the plan.
Of the spec's Expected Surface every touched entry is declared (`pom.xml`, `git-store/`, the specification, `module-structure.adoc`, `AGENTS.md`, `README.adoc`, `doc/README.adoc`). Undeclared: `general.adoc` and the SVG (the GDR-GEN-1 and GDR-GEN-3 updates the Standing Constraints require), explained.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Module creation | shipped-as-specified | `git-store/pom.xml` 21-66 (git-core, JSpecify, cui-java-tools, Lombok provided, test scope only otherwise) and 79-87 (the `module-layering` include `de.cuioss:git-core`, `combine.children="append"`); `module-info.java` with `uses` for the three interfaces; `@NullMarked`; `GitStoreLogMessages` (`GIT_STORE-1`, `-100`) and `LogMessages.adoc`; root `pom.xml` 53; `AGENTS.md` table and `module-structure.adoc` state |
| 2. Registry and configuration (GDR-STORE-1) | shipped-as-specified, plus the anchor-key refusal added at outline | `RepositoryRegistry.of` (alias, remote against the policy, `NumberFormatException` for a port beyond int at 93-95), `StoreConfiguration.from` over `ConfigurationTree` (a parsed `Map`, no YAML library); the anchor key equal to the signing key is `MALFORMED` naming `anchor.signed.key`, never the value (`StoreConfiguration.java` 164-168; `StoreConfigurationTest` 665) |
| 3. Codec and outcome list (GDR-STORE-11, GDR-GEN-3) | shipped-modified (the list gained `MALFORMED` and splits "not supported, managed by source" in two; `general.adoc` and `StoreOutcome` agree) | `Codec`, `StoreOutcome`, `StoreResult` with `PassedOn`; `CodecTest` tests a codec that lives in the test (L2-1) |
| 4. Service interfaces and provider selection | shipped-modified (a duplicate id stops the start only for the configured id; the spec was rewritten to say so, `git-store.adoc` 80-92) | `SigningKeySource`, `AnchorProvider`, `PersonDirectory` with `PersonEntry` and `Membership`; `StoreProviders.load` / `select` / `unique` (128-166); `StoreProvidersTest` covers missing and duplicate ids for all three, the `ServiceConfigurationError` path and `duplicateOfUnconfiguredIdIsIgnored` |

The lane reports 6 deliverables and 6 done; the spec carries 4 (the outline split them, and fix tasks followed: lesson `2026-11-20-07-002`). The narrative's 847 / 883 tests, the two fix rounds (25 findings), Sonar `new_code_issue_count=0` and the green CI are not shown by the diff and are recorded as unverified plan facts; the unticked "`build / conclusion` green" and "review comments handled" in the squash message's Test Plan are the PR body's, not a failure. The native-image claim (git-store ships none) is borne out: no `META-INF/native-image` in `git-store`, and `general.adoc` gives the reason.
The four review-and-Sonar numbers and `cleanup_owed` are `unknown` in the payload (a producer gap the lane named), so they are compared as the claim they are (nothing is asserted, nothing re-read); `merge_state=merged` and the merge commit are corroborated by `ci pr view` (state merged, `9bdc5ba`).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points below at their file and line at the head (`a558afd`; the only change since the merge is the #43 comment). By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: reading a new 15-type module against the spec and the build guards is the depth the dispatch rule exists for, and one landing's read must not share context with the reconciliation of the ten lessons drained in the same scan.
- **Verdict:** layer 1 — 3 of 3 spec claims settled or unsettled as below, 1 premise narrowed during implementation (minor), 1 minor not shown; layer 2 — 3 minor, 1 not shown by reading; layer 3 — no blocker, no major, 5 minor re-read, 1 info.
- **Re-read by the orchestrator:** root `pom.xml` 60-84; `git-store/pom.xml` 21-66, 79-87, 100; `StoreConfiguration.java` 155-181; `StoreProviders.java` 78-90, 125-140, 158-195; `StoreRepository.java` 70-84; `RepositoryRegistry.java` 91-96; `ReferenceUpdater.java` 195-202 in `git-core`; `git-store.adoc` 80-92 and 196-204; `CodecTest.java` 125-130; `DomainTermAbsenceTest.java` 43-47. **Not re-read** (leaf-only, recorded as unverified leads): the `URIish` behaviour of JGit 7.8.0 (the leaf read the sources jar; the code and the spec both cite it, and `portBeyondIntRange` tests it), the `combine.children` merge result of the layering include, `META-INF/services` against `module-info.java` in module mode, the key-spelling and equality tests beyond `StoreConfigurationTest` 665.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | Claim 2: the configuration can be modelled without a third-party dependency in `git-store` | checked | confirms | `git-store/pom.xml` declares no YAML or other new library; `ConfigurationTree` and `StoreConfiguration.from` read an already-parsed `Map` |
| L1-2 | The Done-when: the layering guard rejects a `git-store` dependency on any reactor module other than `git-core` | checked | confirms | root `pom.xml` excludes `de.cuioss:git-*` and includes only `git-testing` in test scope (`searchTransitive=false`); `git-store/pom.xml` 83 appends `de.cuioss:git-core` only |
| L1-3 | `consumer-isolation` of `git-core` is unaffected | checked | confirms | `git-core/pom.xml` is not in the diff; `git-core` gains no dependency |
| L1-4 | Claim 1: the provider-selection rules (exactly one / at most one, refusal on missing or duplicate id) are stated in the spec | checked | refutes (narrowed, not against the claim) | the original text said two providers with the same id stop the start, for any id; `unique` (163) checks the configured id only, and `git-store.adoc` 87-89 now says two candidates with an id nobody configured do not stop the start. The claim "the rules are stated" still holds; the premise it carried was narrowed. Minor, deliberate, recorded in the spec |
| L1-5 | The Done-when: the guard rejects the wrong edge at build time | not shown by reading | none | the diff holds no negative run; the `combine.children="append"` result and the rejection of a second edge are untested here (the same class as PLAN-06's L1-5) |

Persisted onto the spec (`corpus set-verdict`): claim 2 corroborated at `a558afd`. Claim 1 stays unstamped: the finding narrows the premise but does not refute the claim, and a finding that names no corpus claim persists nothing. Claim 0 (the pre-plan observation) cannot be re-read after the plan and stays unstamped.

**Layer 1 verdict:** findings — 1 minor (L1-4), 3 info; 1 not shown by reading (L1-5)

### Layer 2 — Implemented

| Finding | Severity | Claim | Evidence |
|---------|----------|-------|----------|
| L2-1 | minor | Deliverable 3 (GDR-STORE-11) is tested only against a codec that lives in the test (`KeyValueCodec`); `Codec` is a bare interface with no code to fail, so the byte-identity test documents the contract and cannot fail through a `git-store` change | `CodecTest.java` 125-130 |
| L2-2 | minor | provider selection is tested only on the class path (`useModulePath=false`, registrations in `META-INF/services`); the `uses` directives are checked by javac only, so lookup in module mode is not exercised | `git-store/pom.xml` 100; `src/test/resources/META-INF/services` |
| L2-3 | minor | the domain-term list is copied into a fourth module (`git-core`, `git-worktrees`, `git-testing`, `git-store`): the known duplicated-list class of PLAN-06's L3-3, one more copy | `DomainTermAbsenceTest.java` 43-47 |
| L2-4 | info | no file in the diff is unexplained; the narrative figures (counts, Sonar, 6 of 6) are not shown by reading | — |

**Layer 2 verdict:** findings — 3 minor, 1 info; the narrative's plan facts not shown by reading

### Layer 3 — Correct

No blocker, no major. Five minor defects, each re-read at its line; none is fixed here (a layer-3 defect is staged as a plan only on the operator's word).

| Finding | Severity | Where | Triggering input and effect |
|---------|----------|-------|-----------------------------|
| L3-1 | minor | `StoreConfiguration.java` 171-179 against `ReferenceUpdater.java` 199 | `save.max-attempts` has only a lower bound; `2147483647` is accepted (the test rejects `Long.MAX_VALUE` only). `git-core` `swap` loops `for (int attempt = 1; attempt <= attempts; attempt++)`, so with that value `attempt++` wraps and the loop never ends when every try loses its race. Latent until a save consumer uses the value. The same `attempts` overflow class as PLAN-04's P-series: the fix is an upper bound at the configuration and the loop condition in `git-core` |
| L3-2 | minor | `StoreRepository.java` 70-84, `RepositoryRegistry.toString` | the rendering removes user and password only; `URIish.toString()` keeps the path and query, so `https://host/repo.git?token=x` is printed by `StoreRepository.toString`, `RepositoryRegistry.toString` and `StoreConfiguration.toString`. GDR-NFR-3 is wider than "no user information"; nothing refuses a token in the query |
| L3-3 | minor | `StoreProviders.java` 158-175 (`unique`), 163 | `select` is public and calls `idOf.apply(candidate)` unguarded: a candidate whose `id()` throws escapes as an exception, not a `StoreResult`; `loadAll` covers only a `ServiceConfigurationError` while loading. The known "exception escaping a result operation" class of PLAN-04 |
| L3-4 | minor | `StoreProviders.java` 83-86, 138 | `load` instantiates every `AnchorProvider` on the class path even when `anchor.provider` is absent, so a broken anchor provider jar stops a start that does not use it; `GIT_STORE-1` logs the three selected ids while the Javadoc argues a refusal omits the id because a configured value may be secret — the two are inconsistent |
| L3-5 | minor | `StoreConfiguration.java` 164-168 | the anchor-key-equals-signing-key refusal compares the two strings; the same key under another spelling (`/k` and `/./k`) passes. The limit is documented (104-107) and left to the `signed` provider, so GDR-STORE-18's "the start must fail" is approximated |
| L3-6 | info | `StoreResult.java` 43 | `StoreResult<byte[]>` compares by array identity in the record's `equals`; it does not leak (`toString` prints `[B@…`); `PersonEntry` records copy their sets |

Other checks found nothing: `[a-z0-9-]+` is applied with `matches()` (no ReDoS, no newline issue), log ids carry the prefix `GIT_STORE` with no collision with `GIT_CORE` or `GIT_WORKTREES`, `module-info` is correct (`requires transitive` for core and tools, `uses` for the three interfaces).

**Layer 3 verdict:** findings — 5 minor, 1 info; no blocker, no major

### Conformance with the project's rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, automatic module name, `@NullMarked` | conforms | `git-store/pom.xml` 14, `module-info.java`, `package-info.java` |
| AGENTS.md | expected outcomes are results, not thrown | deviates | L3-3: a throwing candidate `id()` escapes `StoreProviders.select` |
| AGENTS.md | `CuiLogger`, `LogRecord` holder, catalogue for every INFO/WARN/ERROR id | conforms | `GitStoreLogMessages` `GIT_STORE-1` / `-100`, `LogMessages.adoc` |
| AGENTS.md | Javadoc on public types and methods; licence header | conforms | all 28 files carry the header |
| AGENTS.md | no dependency without approval; lower modules stay free of third-party dependencies | conforms | `git-store/pom.xml` adds only approved libraries; `git-core/pom.xml` is not in the diff |
| AGENTS.md | a new module adds its edge to `module-layering` in its own POM; `DomainTermAbsenceTest` per module | conforms | `git-store/pom.xml` 79-87; the test exists (the list is copied, L2-3) |
| CLAUDE.md | JGit claims verified and the version named | conforms | the `URIish` port claim cites 7.8.0 (`git-store.adoc`), holds against the sources jar |
| `general.adoc` | GDR-NFR-3: no secret in a result, log, exception message or `toString` | deviates | L3-2 (a token in a remote's query is rendered); refusal details, `Signing.toString`, `Anchor.Signed.toString` and `Member.toString` hide their secrets; the tests use a JUL capture |
| `git-store.adoc` | GDR-STORE-1 and GDR-STORE-11 tested, refusals tested | conforms | `RepositoryRegistryTest`, `StoreConfigurationTest`, `StoreProvidersTest`; `CodecTest` with a test codec only (L2-1) |

## Metrics and Anomalies

- Tokens (landing fact `total_tokens`): 17,241,572 across the run; wall 21,188 s (5.9 h); model `claude-sonnet-5-5`, effort medium; 12 finalize steps all `done`; branch cleanup `merged` through the merge queue. The retrospective's 4,695,078 tokens "floor" and the landing's 17.2M are two different measures (the plan's dispatched tokens against the whole run); both are far over the 2.5M error anchor for a multi_module feature plan.
- Cost drivers recorded by the lane: the unmeasured orchestrator build tier for a new module (two `verification_handback`, three execute re-entries; lesson `2026-11-18-18-003`, second module), four firings of simplify and the pre-push gate (lesson `2026-11-18-07-007`), and two review loop-backs (25 findings; CodeRabbit re-reviews every pushed head).
- Anomalies: the landing's `unresolved_review_threads`, `review_body_findings_*`, `sonar_open_new_issues` and `cleanup_owed` are `unknown` (the producer gap the lane names), so the landing is incomplete by `landing-check` (5 keys); `automatic-review` ended with 1 comment refused-structural and the `wait-region-unified-triage` budget 2 spent, 0 granted.

## Routing / Merge Behavior

Merged through the merge queue (`merge_mechanism=merge_queue`) as a squash; the squash message carries the PR body. The operator then merged #42 (AGENTS.md: plan-marshall runs may merge their own pull request) and #43 (a comment-only change in `StoreConfiguration.java`), both outside the plan. No collision with a concurrently launched plan (PLAN-07 ran alone).

## Reconciliation Actions

- Queue: PLAN-07 transitioned to `shipped`; `pr` 41, `landing` `landings/PLAN-07.md`, `plan_marshall_plan_id` `plan-07-store-module-registry-spi`.
- Spec corpus: PLAN-07 claim 2 corroborated.
- Lessons (10 messages): nine are recurrences of existing lessons (`2026-11-18-13-004` eighth plan, `2026-11-19-06-001` third plan, `2026-11-18-13-003` third plan, `2026-11-18-07-007` twice, `2026-11-18-18-003` second module) and two outline lessons are new (`2026-11-20-14-001`, `2026-11-20-14-002`); the claim that leaves used plain `git` and `sed` is not corroborated by any log and is not promoted.
- epic.md: Open Defects for L3-1..L3-5 and the layer-2 residue (candidates for PLAN-20, on the operator's word), the incomplete-landings entry extended to PLAN-07, queue annotations and decisions.
- Surfaces: `git-store/` is now resolvable by `corpus surfaces`; PLAN-08..11 are re-checked at the next `next`.
