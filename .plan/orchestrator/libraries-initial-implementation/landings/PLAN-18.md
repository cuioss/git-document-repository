# Landing Analysis: PLAN-18 — Collected leftover fixes (git-testing first)

epic: libraries-initial-implementation
workstream: WS-01
pr: #28 (https://github.com/cuioss/git-document-repository/pull/28), merged through the merge queue as `7418f76` (squash, parent `55b0f89`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-18-leftover-fixes-016`
> after verifying it against git, the CI abstraction and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git diff 55b0f89 7418f76`, 10 paths, all under the declared Expected Surface (`git-testing/src/main`, `git-testing/src/test`,
`git-testing/pom.xml`, `doc/specification/git-testing.adoc`); `git-core`, `git-worktrees` and `.github/project.yml` untouched. The PR merged
before PLAN-02's #25 and carries none of its paths.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Four test gaps (a-d) | shipped-modified (two assertions weaker than named, see L2-1 and L2-2) | (a) `shouldRejectAnnotatedParameterOfImplementationType`; (b) closing asserted for local and in-memory, the rollback test retitled; (c) tokens pairwise distinct over 10 draws, usernames not all equal; (d) non-vacuity moved from the static `@AfterAll` into a per-test `@AfterEach`, in `LocalRepositoryExtensionTest` and `LocalRemoteExtensionTest` |
| 2. Cleanup independent of the JUnit setting | shipped-as-specified | `TemporaryRepository` and the new `ClosingRepository` implement `AutoCloseable` and `CloseableResource` with one `close()`; second Surefire execution `store-autoclose-off` (re-read in `git-testing/pom.xml`: distinct `reportNameSuffix`, the setting key equal to the spec's, no include list) |
| 3. `@InMemory` parity | shipped-as-specified (behaviour change chosen) | `InMemoryRepositoryExtension.java:114` `repository.create(true)` links HEAD to `refs/heads/master`; tests of HEAD and the branch name; Javadoc and spec updated |
| 4. Module descriptor on the module path | shipped-modified (the allowed alternative: the limit recorded) | no module-path run; the automatic-module facts and the consumer's obligations are in `doc/specification/git-testing.adoc`; `useModulePath=false` stays |
| 5. Leads (a) `PER_CLASS`, (b) dangling symlink, (c) hand-written doubles | shipped-as-specified, (c) as an accepted exception | (a) `PerClassLifecycleTest`; (b) `TemporaryDirectoriesTest` with a real dangling link, **no production change needed** (lead refuted, L1-4); (c) the doubles stay, with reasons in the spec |

Claims of the landing message against the diff: 5 of 5 deliverables and the second Surefire execution are borne out; "165 tests", "12 CodeRabbit threads answered (11 fixed, 1 taken into account)" and the Sonar figure are run- and provider-side facts the diff neither shows nor contradicts. The message's PR number is #28, not the `create-pr` record `#26` (confirmed: #26 and #27 were closed and re-opened).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at their file and line in `7418f76`, and the JGit sources in the local 7.8.0 sources jar. The reading is by reading, no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing, on the `orchestrator.analyze` surface — reason: reading merged code against the spec is the depth the dispatch rule exists for, and this landing's read must not share context with the sibling landing (PLAN-02) analysed in the same drain.
- **Verdict:** layer 1 findings — 3 minor; layer 2 findings — 5 minor; layer 3 findings — 2 minor, 4 not shown by reading.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | HYPOTHESIS "`DfsRepository#create` links HEAD": confirmed; the pre-change fixture never called `create` | checked | confirms | JGit 7.8.0 `DfsRepository#create`; `InMemoryRepositoryExtension.java:114` |
| L1-2 | HYPOTHESIS "JGit and `cui-test-generator` are automatic modules": both manifests carry `Automatic-Module-Name`, no `module-info.class`; the javac-lint half was never observed | checked | confirms | jar manifests |
| L1-3 | HYPOTHESIS "Jupiter closes a stored `AutoCloseable` only while the setting is true and always a `CloseableResource`" | not_shown_by_reading | none | `LocalRepositoryExtension.java:203`; the engine class was not read; the second Surefire run is what exercises it |
| L1-4 | HYPOTHESIS "`FileUtils.delete(SKIP_MISSING)` skips a dangling symlink": **refuted**. `delete` returns early only when `!fs.exists(f)`, and `FS.exists` delegates to `FileUtils.exists`, which is `Files.exists(..., NOFOLLOW_LINKS)` (re-read in the 7.8.0 sources, `FileUtils.java:150`, `FS.java:1652`, `FileUtils.java:835`); a dangling link is deleted | checked | refutes | `FileUtils.java:150` |
| L1-5 | Spec premise inconsistent with `main`: the Standing Constraints list EasyMock as approved (as does the epic decision of 2026-11-17), while the landed document says adding EasyMock "needs the owner's approval" | checked | refutes | `doc/specification/git-testing.adoc:140` |
| L1-6 | Deliverable 5 (c) named `GitCredentialGeneratorTest` as holding a hand-written double; it holds none, and the document says so | checked | refutes | `doc/specification/git-testing.adoc:179` |
| L1-7 | The OBSERVED claims of the spec, re-read at `55b0f89`, were all true | checked | confirms | `LocalRepositoryExtensionTest.java`, `InMemoryRepositoryExtensionTest.java` |

Persisted onto the spec with `corpus set-verdict` (producer `libraries-initial-implementation/analyze`, at `7418f76`): claims 0-4 corroborated, 5 unverifiable, 6 corroborated, 7 corroborated, 8 contradicted (rescoped: no). L1-5 and L1-6 are premises of deliverable 5 (c), not claims of the section: recorded here only.

**Layer 1 verdict:** findings — 3 minor (L1-4, L1-5, L1-6); 1 not shown by reading (L1-3)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1a | `InMemoryRepositoryExtension` | `shouldRejectAnnotatedParameterOfImplementationType` fails if `requireType` loosens. **The positive half is tautological** (`InMemoryRepositoryExtensionTest.java:268`: it compares the test's own signature to `Repository.class` and checks `instanceOf Repository`, which any DFS subclass passes) (L2-1) | none | checked |
| 1b | `LocalRepositoryExtension` | closing on rollback is asserted by calling `rollBack` directly with a `CountingRepository` (`LocalRepositoryExtensionTest.java:332-382`); **the call from `createBareRepository` to `rollBack` is unobserved**, so passing `null` there survives; the test whose title claimed closing was retitled "delete the directory" (L2-2) | none | checked |
| 1c | `GitCredentialGenerator` | `GitCredentialGeneratorTest.java:77`: pairwise-distinct tokens over 10 draws, usernames not all equal; weaker than "user and token differ" by design, said in the comment | none | checked |
| 1d | `LocalRepositoryExtension`, `LocalRemoteExtension` | per-test `@AfterEach` counts annotated parameters against the directories the test recorded | none | checked |
| 2 | `ClosingRepository.java`, `LocalRepositoryExtension.java:203` | store-type tests for local and in-memory; none for `@Remote`; **no test asserts the parameter reached the engine** (only a comment in `PerClassLifecycleTest.java:232` names the key, re-read), so a mistyped key makes run 2 identical to run 1 (L2-3) | `rollBack` and two `NAMESPACE` constants widened to package-private, a new in-memory initialisation failure path: all tested and Javadoc'd | checked |
| 3 | `InMemoryRepositoryExtension.java:114` | `shouldLinkHeadToMaster`, `shouldStartOnSameBranchAsLocalRepository`, `shouldLinkHeadWhenInitialising`, `shouldCloseRepositoryWhenInitialisationFails` | the plan's decision log for deliverable 3 is not in the diff | checked |
| 4 | `doc/specification/git-testing.adoc:61` | n/a (recorded limit); the `IllegalAccessError` reason is carried over from the POM without a source; a `ModuleDescriptor.read` test of `module-info.class` was not tried | none | checked |
| 5 | `PerClassLifecycleTest`, `TemporaryDirectoriesTest` | both fail on the property they guard; (c) is recorded, with reasons that are partly convenience ("a record is shorter than a mock") and one circular (needs approval against the spec's approved list) (L2-4) | none | checked |

Done-when "each assertion of deliverables 1 and 2 fails when the property it guards is broken, shown by a recorded mutation or by construction": by construction mostly yes; **no recorded mutation is in the diff**, so the PR body's "shown to fail by mutation" cannot be checked by reading (L2-5, not shown by reading).

**Layer 2 verdict:** findings — 4 minor (L2-1 to L2-4); 1 not shown by reading (L2-5)

### Layer 3 — Correct

No blocker or major defect. Re-read by the orchestrator at `7418f76`:

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `LocalRepositoryExtension.java:210-217` (`TemporaryRepository.close`: `try { repository.close(); } finally { delete }`) | `close()` throws a `RuntimeException` and the deletion then throws an `IOException` | the `IOException` replaces the first failure, which is lost (no `addSuppressed`), unlike `rollBack` (lines 171-185) | minor |
| L3-2 | `LocalRepositoryExtension.java:35` (class Javadoc: "A constructor parameter is resolved per test method as well") and the "cleaned up per test" wording of the three extensions | `@TestInstance(PER_CLASS)` | the public Javadoc is false there (Jupiter hands the class context, and the plan's own test shows it); only the specification was corrected | minor |

**Layer 3 not shown by reading:** exactly-once closing of a value implementing both interfaces (only a run shows it; `PerClassLifecycleTest` counts 1); the `CloseableResource` deprecation (`@SuppressWarnings("deprecation")` would not cover a `forRemoval` warning on a later Jupiter); flakiness under parallel test execution (`shouldCreateNoDirectory` reads a JVM-global counter and the failed-creation test swaps the global `SystemReader`, guarded by `@Isolated`; no `junit-platform.properties` in the module, the parent configuration was not read); order dependence of `PerClassLifecycleTest` (none found, `InMemoryClosingObserver` couples to the store key layout and fails loudly).

**Layer 3 verdict:** findings — 2 minor; 4 not shown by reading

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS | conforms | `module-info.java` unchanged; `ClosingRepository` is package-private |
| AGENTS.md | `@NullMarked`, `@Nullable` | conforms | `package-info` unchanged; new code uses `@Nullable` |
| AGENTS.md | Javadoc on every public type and method | conforms | no new public type or method; public Javadoc partly stale (L3-2) |
| AGENTS.md | License header | conforms | `ClosingRepository.java`, `PerClassLifecycleTest.java` carry it |
| AGENTS.md | JUnit 5, `cui-test-generator`, EasyMock for test doubles | deviates | six hand-written doubles, recorded as accepted exceptions with weak reasons (L2-4); EasyMock is on no test class path of `git-testing` |
| AGENTS.md | No dependency without approval | conforms | the POM diff adds a Surefire execution only |
| AGENTS.md | Every external statement cites its source | deviates | the POM's `IllegalAccessError` explanation and Jupiter's auto-close semantics are cited only by code comment |
| CLAUDE.md | Verify JGit claims, name the version | conforms | 7.8.0 named; `DfsRepository#create`, `FileRepository#create`, `FileUtils#delete` read in the local sources jar |
| CLAUDE.md | Do not touch `.github/project.yml` | conforms | not in the diff |
| AGENTS.md | Maven and JUnit only; no ad-hoc verification classes | not_shown_by_reading | none in the diff; `verify` and the 165-test count need a run |

## Metrics and Anomalies

- Tokens: 15,844,675; finalize carried about half of them (the lane's own figure: 49 %).
- Duration: 28,169 s (about 7.8 h).
- Anomalies: the `create-pr` step recorded #26; the automatic-review rate-limit recovery closed and re-opened the PR as #27 and #28 without an operator question before the first two (247K retryable tokens in two `blocked_session_restart` dispatches). CodeRabbit never reviewed the final commit `e5d07ec` (a pure helper rename for Sonar S6213); the merge was made on the operator's explicit instruction. All 24 dispatch-boundary rows carry no `--step-id`; `effort` is `unknown`. The finalize triage's inline edits were refused by the commit gate until a sanction existed (one commit went under `--step sonar-roundtrip`), and the pre-push gate was recorded after the whole-tree verify only for the last rename commit.

## Routing and Merge Behavior

- Review: CodeRabbit refused on its free quota for most of the run; Sourcery (optional) was refused throughout. **Provider re-read:** `review-gate` for #28 returns `unresolved_review_threads: 0` and `review_body_findings_total: 12`, `review_body_findings_handled: 0` with `merge_allowed: false` (`body_findings_undisposed`). The landing's three review numbers are `unknown`, so there is no difference to record; the zero for `handled` is the parser's, because the dispositions live in the run's progress file, which was not read: `review_body_findings_handled` is **not re-verified**. The merge was made against that gate state on the operator's explicit instruction ("Merge PR #28 now").
- CI/merge: merged through the merge queue as a squash, `7418f76`; no rebase conflict reported.
- Sonar: the landing claims `sonar_open_new_issues=0` for head `e5d07ec` (gate OK, new coverage 100 %, hotspots 0). This analysis reads the quality gate of #28 as `OK` (new coverage 100.0 %, hotspots 100 % reviewed). The `search` verb lists two S6213 issues (`LocalRemoteExtensionTest`, `LocalRepositoryExtensionTest`) without a status column; the landing says the last commit renamed the helper to clear them. The open count of 0 is **not re-verified**.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-18 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-18 --field pr --value 28`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-18 --field landing --value landings/PLAN-18.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-18 --field plan_marshall_plan_id --value plan-18-leftover-fixes`
- [x] epic.md narrative reconciled (the PLAN-18 annotation and Watch retired, decisions, defects)
- [x] layer-1 verdicts persisted onto the spec (claims 0-4, 6, 7 corroborated; 5 unverifiable; 8 contradicted)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- The operator's choice for leftovers (fold into the staged git-core plans) covers `git-core` only. The leftovers of this landing in `git-testing` (L1-5, L1-6, L2-1 to L2-5, L3-1, L3-2) have no staged spec to fold into: they are recorded as a **Watch** in epic.md, to be folded into the next spec that touches `git-testing/`, or to be staged as a second collection plan on the operator's word. Nothing was staged.
- Incomplete landing: `inbox landing-check` reports `complete: false` (missing `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`). Recorded in the same Open Defect as PLAN-02's.
- Lessons: the three tooling lessons in this plan's inbox joined existing or new entries (`2026-11-18-13-002`, `-004`, `-005`, `2026-11-18-07-006`); the eleven lessons about this repository's own test code were discarded as shipped, their rules being embodied in the merged tests and the POM.
- The question that PLAN-02 left to PLAN-18 (how `git-core` consumes `git-testing`: class path, `useModulePath=false`) is answered by deliverable 4: the limit is recorded in `doc/specification/git-testing.adoc`, no module-path run exists.
