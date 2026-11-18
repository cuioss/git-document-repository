# Landing Analysis: PLAN-01 — git-testing: dependency management, credential generator, repository fixtures

epic: libraries-initial-implementation
workstream: WS-01
pr: #23 (https://github.com/cuioss/git-document-repository/pull/23), squash merge commit `6cc64f0c6512d139846af9edca670da3a1032032` (parent `655e3de`)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims against ground truth
> (actual code, artifacts, PR state) — a pasted claim is a lead, never a fact. Source: inbox message
> `git-testing-fixtures-017.md` (plan `git-testing-fixtures`, inbox scan).

## Deliverable Fidelity vs Spec

PR state read through the CI abstraction: `pr_state: merged`, `merge_commit_sha 6cc64f0`, merge-group run `44253740450`
completed with success. The merge commit changes 31 files (+2350/-39).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Root `pom.xml` dependency management and `git-testing/pom.xml` | shipped-modified | Only JGit `7.8.0.202609011348-r` was added to the root `dependencyManagement` (`pom.xml:40-49`); the parent chain already manages the rest of the approved test stack (verify-first clause contradicted, see Layer 1). `git-testing/pom.xml:18-38` declares JSpecify, JGit, `cui-test-generator`, `junit-jupiter-api` (the last two overridden to compile scope); `module-info.java:19-25` has `exports` and `requires transitive`. |
| 2. Credential generator (GDR-TEST-2) | shipped-as-specified | `GitCredentialGenerator`, `GitGenerators` and a new own record `GitCredential(String username, char[] token)` (`GitCredential.java:32`) in `de.cuioss.git.document.testing`; no reactor dependency. Tests: `GitCredentialGeneratorTest`, `GitCredentialTest`. Source commit `287e1a86` exists in pm-mcp-core. |
| 3. Repository fixtures (GDR-TEST-1) | shipped-as-specified | `@LocalRepository`, `@InMemory`, `@Remote` with `LocalRepositoryExtension`, `InMemoryRepositoryExtension`, `LocalRemoteExtension`, `LocalRemote`, `TemporaryDirectories`; in-memory value typed as `Repository`; `file:` URL by `Path.toUri()`. |
| 4. Fixture tests and docs | shipped-modified | Tests exist for every fixture plus `CombinedFixturesTest`, but two property claims of the PR test plan are not asserted (see Layer 2). `doc/specification/git-testing.adoc` and `AGENTS.md` "Current state" updated. |
| (unplanned) | added | Seven documentation files outside the declared surface were touched: `README.adoc`, `doc/README.adoc`, `doc/clients/pm-mcp-core.adoc`, `doc/concepts/git-as-document-persistence.adoc`, `doc/concepts/module-structure.adoc`, `doc/specification/general.adoc`, `doc/specification/git-core.adoc` (stale "JGit approval pending" statements; two Q-Gate findings during outline and plan, see candidate lessons 001 and 002). |

Surface delta (`inbox landing-check`, declared 7 paths against realized 31 at base `655e3de`): `expansion_detected`,
`added` = the seven files above, `missing` = none.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace and the merged code — by one read-only
leaf (`execution-context-level-3`, resolved on the `orchestrator.analyze` surface), and the orchestrator re-read the
`checked` points named below at their `file:line` before writing them here. The reading is **by reading, no build, no
test run, no write**: what only a run could show is tagged `not shown by reading`, never left as clean.

- **Dispatch:** one read-only leaf per landing — reason: reading merged code is the depth the dispatch rule exists for, and this is the only landing in the drain.
- **Verdict:** layer 1 findings (1 minor, 1 contradicted clause, rest confirmed); layer 2 findings (4 minor); layer 3 findings (2 minor) and 3 not shown by reading —
  no blocker and no major finding on any layer.

Re-read by the orchestrator: `GitCredential.java:32`; `git-testing/pom.xml:14-55`; `pom.xml:38-50`; `module-info.java:17-26`;
`InMemoryRepositoryExtensionTest` (`getRefs()` assertion, wrong-type test with `@InMemory String`);
`LocalRepositoryExtensionTest` (`@AfterAll everyDirectoryIsDeleted`, `shouldRollBackOnUncheckedFailureOfCreation`);
`GitCredentialGeneratorTest` (no assertion of two differing credentials: no `assertNotEquals`, no random reference);
`LocalRepositoryExtension` (`TEST_METHOD` scope, `catch (IOException | RuntimeException e)`, `TemporaryRepository implements AutoCloseable`);
`InMemoryRepositoryExtension` (scope, `new InMemoryRepository(...)` stored as `AutoCloseable`, no `create`);
`TemporaryDirectories` (owner-only `rwx------`, `FileUtils.delete(file, SKIP_MISSING)`, `NoSuchFileException` handling);
`LocalRemoteExtension` (scope, `toUri()`); `InMemory.java` Javadoc (no mention of HEAD);
pm-mcp-core `GitCredentialGenerator.java:15` and `GitOperations.java:300`; commit `287e1a86` in pm-mcp-core.
**Not re-read, recorded as unverified leads:** the claims about the internals of JGit 7.8.0 (`FileUtils.java:81,148-151`,
`DfsRepository.create`, `FileRepository.create`, `BaseRepositoryBuilder`, `URIish`) and of `junit-jupiter-engine` 6.1.3
(`DefaultJupiterConfiguration`), which the leaf read from sources it extracted into its own scratch space; the line
numbers of `~/.m2` pom reads (`cui-java-bom` 1.7.7); the client-doc passage at `doc/clients/pm-mcp-core.adoc:111`.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | HYPOTHESIS: `GitCredentialGenerator` produces `GitOperations.GitCredential`, so an unchanged move would make `git-testing` depend on `git-core` | checked | confirms | pm-mcp-core `GitCredentialGenerator.java:15`, `GitOperations.java:300` (re-read) |
| L1-2 | HYPOTHESIS: pm-provider-git's tests carry no reusable local-remote fixture | checked | confirms | pm-mcp-core `PortedFixtures.java:149-192` (leaf; the file's role not re-read) |
| L1-3 | Verify-first clause: `cui-java-parent` 1.7.7 does not already manage the JGit / test-stack versions | checked | refutes | parent chain manages the test stack but not JGit; `pom.xml:40-49` (re-read) shows only JGit added; `.m2` poms read by the leaf only |
| L1-4 | The client side of the credential switch-over (a different record type, `secret` renamed `token`) is undefined by the spec | not_shown_by_reading | none | `doc/clients/pm-mcp-core.adoc:111` |
| L1-5 | Done-when: `verify` passes at the head, Sonar open-finding read is 0 | not_shown_by_reading | none | needs a build and a provider read |

Persisted as corpus verdicts on `PLAN-01` (producer `libraries-initial-implementation/analyze`, checked at `6cc64f0`):
claim 3 `corroborated`, claim 4 `corroborated`, claim 5 `contradicted` (rescoped: no — the spec was not rewritten; the
landing narrowed the work itself).

**Layer 1 verdict:** findings — 1 contradicted clause (L1-3), 2 not shown by reading (L1-4, L1-5); both hypotheses confirmed.

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `pom.xml:40-49`, `git-testing/pom.xml:18-38`, `module-info.java:19-25` | the build itself | none | checked |
| 2 | `GitCredentialGenerator.java:30-60`, `GitGenerators.java:37-39` | `GitCredentialGeneratorTest`, `GitCredentialTest` — but no test asserts two credentials differ (L2-3) | none | checked |
| 3 | `LocalRepositoryExtension.java:133-155`, `InMemoryRepositoryExtension.java:86`, `LocalRemoteExtension.java:89` | per-fixture tests, `CombinedFixturesTest` | none | checked |
| 4 | tests under `git-testing/src/test/java/` | "in-memory type not leaked" is claimed by the PR test plan and not asserted (L2-1); repository close is not asserted (L2-2); one teardown assertion has no non-vacuity guard (L2-4) | the PR claim "asserted by the module's own tests" is only partly borne out | checked |

Findings (all minor, none blocking): **L2-1** the only wrong-type test uses `@InMemory String` (`InMemoryRepositoryExtensionTest`, `shouldRejectAnnotatedParameterOfAnotherType`), so nothing fails if the DFS type leaks; **L2-2** `shouldRollBackOnUncheckedFailureOfCreation` is titled "close the repository and delete its directory" and asserts only the cause and the missing directory; **L2-3** no randomness assertion for the generator; **L2-4** `LocalRepositoryExtensionTest.everyDirectoryIsDeleted` asserts over a possibly empty stream (`LocalRemoteExtensionTest` and `CombinedFixturesTest` guard, and the lesson candidate 009 explains why a non-empty assertion cannot sit in `@AfterAll` for single-test runs).
No file of the diff is unexplained; every landing-narrative claim the leaf could check is borne out except L2-1 and L2-2.

**Layer 2 verdict:** findings — 4 (all minor)

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `LocalRepositoryExtension.java` (`TemporaryRepository implements AutoCloseable`), `InMemoryRepositoryExtension.java:86-88`, `LocalRemoteExtension.java:87-89` | a consumer sets `junit.jupiter.extensions.store.close.autocloseable.enabled=false` (or runs an older Jupiter) | temporary directories and open repositories leak with no failure; default is `true`, so no effect on the default path | minor |
| L3-2 | `InMemoryRepositoryExtension.java:86` against `LocalRepositoryExtension.java:144` | code under test reads HEAD or tests that the repository exists | `@InMemory` has no HEAD while the bare fixtures link HEAD to the initial branch; `InMemory` Javadoc (lines 25-35, re-read) does not say so | minor |

**Layer 3 not shown by reading:** L3-3 — the module descriptor is never exercised on the module path (`useModulePath=false`, `git-testing/pom.xml:45-51`, re-read); JGit and `cui-test-generator` 3.0.2 are automatic modules, so how `git-core` and `git-worktrees` behave with `git-testing` in test scope is not covered by any test, and only a run would show it. L3-4 — a dangling symbolic link in a fixture tree may be skipped by `FileUtils.delete(..., SKIP_MISSING)` and then fail the directory delete (`TemporaryDirectories.java:96-122`; the `FS.exists` behaviour was not read). L3-5 — fixtures under `@TestInstance(PER_CLASS)` (the `TEST_METHOD` scope is tested only with the default lifecycle).

**Layer 3 verdict:** findings — 2 (both minor), not shown by reading — 3

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS `module-info.java`, automatic module name `de.cuioss.git.document.testing` | conforms | `git-testing/pom.xml:16`, `module-info.java:19` |
| AGENTS.md | `@NullMarked` per package | conforms | `package-info.java:19-22` |
| AGENTS.md | expected outcomes returned, not thrown | conforms | only misuse and infrastructure failures throw `ParameterResolutionException` |
| AGENTS.md | `CuiLogger` and a `LogRecord` holder per module | conforms (vacuous) | no logging statement in the module |
| AGENTS.md | tests with JUnit 5, `cui-test-generator`, EasyMock for doubles | **deviates** | EasyMock is not declared in `git-testing/pom.xml` (re-read) and not used; doubles are hand-written (`FailingListingFileSystem`, `SystemReader.Delegate` subclasses) — collected in PLAN-18 |
| AGENTS.md | no third-party dependencies pulled into lower modules; `git-testing` depends on no reactor module | conforms | `git-testing/pom.xml:18-38` |
| AGENTS.md | Javadoc on every public type and method | conforms | all public types read; generation itself not run |
| AGENTS.md | license header on every source file | conforms | headers seen; the pre-commit plugin was not run |
| Spec | no `pm-*` domain term in a public type; package renamed | conforms | all 12 main classes read |
| Spec | no dependency beyond the approved list; `.github/project.yml` untouched | conforms | no `.github` path in the 31-file diff |
| CLAUDE.md | verify JGit API claims against documentation or source, name the version | not_shown_by_reading | JGit 7.8.0 is named in the docs and the pom; the leaf grounded most calls in the 7.8.0 sources, but `SystemReader.Delegate.openUserConfig`, push/fetch over a `file://` URL with a trailing slash, the commit-building calls in `commitOnBranch` and `FS.exists` were not grounded |

## Metrics and Anomalies

- Tokens: 20,237,347 in total (landing message). The plan's own retrospective candidate lesson 016 reports 3,871,880 dispatched tokens as a floor against the 2.5M multi-module error anchor, with 1-init unmeasured, so the two figures are not the same measurement; neither was re-measured here.
- Duration: wall 82,387 s (about 22.9 h, mostly an overnight operator wait per lesson 016); worked time 71 min.
- Anomalies: `wait-region-unified-triage` loop-back budget spent four times (one operator-approved extra round); `finalize-step-simplify` ran 7 times; subagent commits carry a `Co-Authored-By: Claude Opus 5.5` trailer while the session model is Sonnet 5.5 (landing residue, not checked against the commit log); `effort=low` reported with a run of this size.

## Routing and Merge Behavior

- Review: CodeRabbit; six inline findings, each answered and resolved per the landing. Read back against the provider with `runbook_lane review-gate --pr-number 23` on the merged head: `unresolved_review_threads: 0` and `review_body_findings_total: 6` — both match what the landing records in prose, while the landing's own facts block carries `unknown` for both (an unobserved fact, not a false claim). `review_body_findings_handled`: **not re-verified** — the review gate reports 0 handled because it reads dispositions from a progress file it was not given, and no progress file was read.
- CI/merge: all 15 checks green per the landing; merged through the merge queue as a squash commit with no rebase or force-push; `pr queue-state` confirms `merged`.
- Sonar: `sonar_open_new_issues` is `unknown` in the landing (post-merge read undecidable, `head_revision_unavailable`); the landing's prose reports 0 new-code issues at head `0822d2f` before the merge. This analysis did not re-read Sonar, so the figure stays `unknown`, never `0`. S5443 on `TemporaryDirectories` was fixed in code on the operator's instruction (owner-only temporary directory), not dismissed.
- Collisions: none — no other plan was in flight.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-01 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-01 --field pr --value 23`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-01 --field landing --value landings/PLAN-01.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-01 --field plan_marshall_plan_id --value git-testing-fixtures`
- [x] epic.md narrative reconciled (Open Defect for the incomplete landing, Watch for layer-3 leftovers, queue annotations, decisions)
- [x] defect/watch opened
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Layer-2 and layer-3 leftovers and the EasyMock deviation: staged as PLAN-18 (`leftover-fixes`), a collection plan held back from `next` until the operator releases it (operator decision, 2026-11-18).
- Surface expansion of PLAN-01 (seven undeclared documents): no staged spec needs correcting — PLAN-02..PLAN-17 already declare the documents they will touch, and the landed statements they would have changed are done.
- Candidate lessons 001-016: tooling lessons filed in the local lessons store (`2026-11-18-07-001` to `-007`); code lessons discarded as shipped (see Decisions in `epic.md`).
- The client side of the `GitCredential` change (L1-4) belongs to the `pm-mcp-core` switch-over, already a Watch.
