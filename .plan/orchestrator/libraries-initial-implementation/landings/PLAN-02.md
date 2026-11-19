# Landing Analysis: PLAN-02 — git-core: take over hardening, remote policy, identity, results and repository operations

epic: libraries-initial-implementation
workstream: WS-01
pr: #25 (https://github.com/cuioss/git-document-repository/pull/25), merged through the merge queue as `2c728bc` (squash, parent `7418f76`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `git-core-import-013` after
> verifying it against git, the CI abstraction and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git diff 7418f76 2c728bc`, 30 paths (9 main classes, `module-info.java`, 11 test files, `git-core/pom.xml`,
`git-core/doc/LogMessages.adoc`, 7 documents). The PR merged after PLAN-18 (#28) and carries none of its paths.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Poms, `module-info.java`, `LogRecord` holder, `RefusingFS`, `CapabilityRefusedException`, `RemotePolicy` | shipped-as-specified | `git-core/pom.xml`, `module-info.java`, `GitCoreLogMessages`, `RefusingFS`, `RemotePolicy` with `RefusingFSTest`, `RemotePolicyTest`. The root `pom.xml` was not touched (PLAN-01 had put JGit into `dependencyManagement`) |
| 2. `GitResult` / `GitOutcome` vs GDR-GEN-3, `GitIdentity` | shipped-modified (the decision the spec left open) | option A: `GitOutcome` has 20 constants and the GDR-GEN-3 table in `doc/specification/general.adoc` has 20 rows, same names, worktree outcomes included (re-counted by the orchestrator); `GitIdentity` with `GitIdentityTest` |
| 3. `RepositoryOperations` and `JGitRepositoryOperations` | shipped-as-specified | open, init, `cloneIfMissing` (new code), log, fetch, push with lease and credentials per call; every repository on `RefusingFS` |
| 4. Cache repository and split tests | shipped-as-specified | `initCache`, `fetchCommit`, `readFile`; `JGitCacheRepositoryTest`, `UnservedWantTest`, `AbsenceContractsTest`, `RepositoryOperationsRecordsTest`; helpers `TestCommits`, `RecordingTransportProtocol`, `TransportFailuresTest` are new |
| 5. Documentation | shipped-modified | the six declared documents plus `README.adoc` and `doc/README.adoc` (two Q-Gate under-coverage findings, resolved by the operator) |

Claims of the landing message against the diff: 5 of 5 deliverables, the option-A decision, the two hardening fixes
(system git config loading, HTTP redirect targets) and the three residuals are all borne out. `unresolved_review_threads`,
`review_body_findings_total`, `review_body_findings_handled` and `effort` are `unknown`; see Routing.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points recorded below at
their file and line in the merge commit `2c728bc` before writing them here. The reading is by reading, no build, no test
run, no write; what only a run could show is tagged `not shown by reading`.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing, on the `orchestrator.analyze` surface — reason: reading the merged code against the spec is the depth the dispatch rule exists for, and this landing's read must not share context with the sibling landing (PLAN-18) analysed in the same drain.
- **Verdict:** layer 1 clean (2 minor spec inconsistencies, 1 `not shown by reading`); layer 2 findings — 2 minor; layer 3 findings — 1 major, 8 minor.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | Verify-first GDR-CORE-11 settled as *producing*: an abbreviation is produced, never resolved as an identity | checked | confirms | `GitIdentity.java:140`; `doc/specification/git-core.adoc` |
| L1-2 | Verify-first GDR-CORE-1: the exception is a `git-core` class under JGit's unchecked `JGitInternalException`; the spec says so | checked | confirms | `CapabilityRefusedException.java:32`; `git-core.adoc` |
| L1-3 | Verify-first module path: settled as class path (`useModulePath=false`); the diff has no evidence a module-path run was tried | not_shown_by_reading | none | `git-core/pom.xml:48` |
| L1-4 | HYPOTHESIS "the bare clone has no counterpart in the source": confirmed, `cloneIfMissing` is new code | checked | confirms | `JGitRepositoryOperations.java:208` |
| L1-5 | Expected Surface listed the root `pom.xml` as an edit while Dependencies says PLAN-01 had already supplied JGit; the root POM was rightly not touched | checked | refutes | `pom.xml` |
| L1-6 | HYPOTHESIS "catalogue under `src/main/resources/`": refuted, it is `git-core/doc/LogMessages.adoc` | checked | refutes | `git-core/doc/LogMessages.adoc:1` |

Persisted onto the spec with `corpus set-verdict` (producer `libraries-initial-implementation/analyze`, at `2c728bc`): claim 6
corroborated, claim 7 corroborated, claim 8 corroborated, claim 9 unverifiable. L1-5 and L1-6 concern the Expected Surface, not a claim of the section, so no verdict is persisted.

**Layer 1 verdict:** clean — 2 minor surface inconsistencies, 1 not shown by reading

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `RefusingFS.java:53`, `RemotePolicy` | `RefusingFSTest`, `RemotePolicyTest`; hook refusal proven by a marker file, `JGitRepositoryOperationsTest.java:953-981` | none | checked |
| 2 | `GitOutcome.java:28`, `GitResult.java` | `GitIdentityTest`; **no test ties the 20 constants to the GDR-GEN-3 table**, the parity holds by reading only (L2-1) | none | checked |
| 3 | `JGitRepositoryOperations.java` | `JGitRepositoryOperationsTest`, `TransportFailuresTest` (lease, rejected, unreachable, credentials) | none | checked |
| 4 | `JGitRepositoryOperations.java:595` | `JGitCacheRepositoryTest`, `UnservedWantTest` | none | checked |
| 5 | documents | n/a | `README.adoc`, `doc/README.adoc`, `git-core/doc/LogMessages.adoc` named by no deliverable of the staged spec (consequences of the standing "update state" rule and of deliverable 1) | checked |

Findings: **L2-1** no test ties `GitOutcome` to the GDR-GEN-3 table (minor); **L2-2** the redirect tests call the guard and the
factory directly, never through `fetch` (confirms the landing's residual, minor); **L2-3** the remote-side hook refusal is untested
(confirms the residual, minor); **L2-4** `AbsenceContractsTest` has tautological assertions (`assertNull` on a value just passed
in; re-read at line 36-50 for the generated input; the rest by the leaf), minor. The three Done-when conditions that reading can
settle hold: each of GDR-CORE-1/2/3/7/8/11 appears in a test display name, no test reaches the network (only `.invalid` hosts with
refusing policies or an in-process transport), and `GitOutcome` equals the table. `verify` and the Sonar read are not shown by reading.

**Layer 2 verdict:** findings — 4 minor

### Layer 3 — Correct

No blocker. Re-read by the orchestrator at the cited lines of `2c728bc`: every row below.

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `JGitRepositoryOperations.java:93-99` (Javadoc), `doc/README.adoc:112-117` | any application that does not install `RefusingFS.systemReader` | GDR-CORE-1 ("must refuse the start of external processes") is met only in part by default: JGit 7.8.0 may run `git --version` and `git config --system` once per JVM through the process-wide `SystemReader`, outside `RefusingFS`. **The limit is documented** in the Javadoc and in `git-core.adoc`, and the landing named it as a residual; the documents still list GDR-CORE-1 as *implemented* | **major** |
| L3-2 | `JGitRepositoryOperations.java:460-470` | a refspec onto the checked-out branch, or ref lock contention | `fetch` maps `getTrackingRefUpdates()` to the result's updates and returns `OK`; a failed update (`LOCK_FAILURE`, `REJECTED_CURRENT_BRANCH`, `IO_FAILURE`) shows only in a per-update status string, while the clone calls `requireMirroredRefs`. A caller that reads `OK` as "synced" reads stale refs | minor |
| L3-3 | `JGitRepositoryOperations.java:362-375` | a remote that stalls | `fetch`, `push` and `cloneIfMissing` set no transport timeout (JGit's default is infinite); only `fetchCommit` sets 60 s (line 595). The call blocks forever | minor |
| L3-4 | `JGitRepositoryOperations.java:296-306` | a clone directory meant to be served to another OS user | the staging directory is `Files.createTempDirectory` (mode `rwx------`) and the atomic move publishes it, so the clone ends up `0700` regardless of umask. Effect on ACL or non-POSIX setups not shown by reading | minor |
| L3-5 | `JGitRepositoryOperations.java:222` (and the config write at 360-364) | `https://<token>@host/...` | only the password is stripped (`setPass(null)`); the user information is written to `<gitDir>/config` in clear text, while the GDR-NFR-3 table says tokens are never stored. Details and logs are clean | minor |
| L3-6 | `RepositoryOperations.java:168` | logging a `FetchCommitInput` with a credentialed URL | the generated `toString` of `FetchCommitInput` (and `RemotePolicy`) renders the URL with user information and query; only `CloneInput` overrides it | minor |
| L3-7 | `GitResult.java:56` | an anonymous exception class (empty simple name), `open(Path.of(""))` | a blank detail throws `IllegalArgumentException` out of an operation that promises a result | minor |
| L3-8 | `RepositoryOperations.java:114` | a refused `pre-push` hook | the Javadoc says "nothing was sent"; JGit 7.8.0 `PushProcess` runs the hook after reading the remote's advertisement (its constructor Javadoc, re-read in the 7.8.0 sources jar), so the remote was contacted | minor |
| L3-9 | `JGitRepositoryOperations.java:214` | an existing bare repository of another remote | `cloneIfMissing` returns it as `OK` without comparing the origin (the source's `REMOTE_ORIGIN_MISMATCH` was dropped); documented | info |

**Layer 3 not shown by reading:** `readFile` reads a whole blob into memory with no bound (an `OutOfMemoryError` is not a result and is visible only in a run); `open`/`log` walk up to the parent repository (documented); `RefusingFS.setExecute` ignores a stricter umask (documented); thread safety of the shared `RefusingFS` instance and JVM-wide behaviour.

**Layer 3 verdict:** findings — 1 major, 8 minor (reported to the operator at once, see Follow-Ups)

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, automatic module name | conforms | `module-info.java:19-26` exports the package; class-path tests (`pom.xml:48`), module-path run not shown |
| AGENTS.md | `@NullMarked` per package | conforms | `package-info.java` |
| AGENTS.md | Expected outcomes are results, not exceptions | deviates | `GitResult.java:56` blank-detail exception; unmapped `RuntimeException` in `fetchCommit` (606-616) |
| AGENTS.md | `CuiLogger` and a `LogRecord` holder, catalogue | conforms | `GitCoreLogMessages` (INFO 1, WARN 100/101) and `LogMessages.adoc` agree; `LogAsserts` in tests |
| AGENTS.md | JUnit 5, `cui-test-generator`, EasyMock for test doubles | deviates | no EasyMock in the module; hand-written `RecordingTransportProtocol` and `SystemReader.Delegate` doubles (arguably justified for a registered `TransportProtocol`) |
| AGENTS.md | No dependency without approval | conforms | `git-core/pom.xml`: jspecify, JGit, cui-java-tools, lombok (provided); test scope git-testing, JUnit, `cui-test-generator`, `cui-test-juli-logger` |
| AGENTS.md | Javadoc on public types and methods; license header | conforms | grep over `git-core/src` finds no file without the header |
| CLAUDE.md | Verify JGit claims and name the version | conforms | Javadoc and the spec name 7.8.0 with a tag URL; the leaf re-verified `TransportHttp`, `PushProcess`, `FS.runInShell` |
| CLAUDE.md | Do not touch `.github/project.yml` | conforms | no path under `.github/` |
| Spec | No PM-MCP term in a public type (GDR-GEN-1) | conforms | none of `pm-mcp`, `planmarshall`, `worktree-sha` found |
| Spec | Secrets (GDR-NFR-3) | deviates | details and logs clean; user information persisted (L3-5), generated `toString` (L3-6) |

## Metrics and Anomalies

- Tokens: 20,926,368 (the largest plan so far; PLAN-01 and PLAN-18 ran at roughly 15-16 million).
- Duration: 26,932 s (about 7.5 h), of which about 56 minutes were a CodeRabbit rate-window wait for a one-line Sonar test fix.
- Anomalies: the review loop did not converge, four triage rounds (23, 27, 1, 1 actionable findings), the loop-back cap of 3 was lifted by the operator for the fourth; `effort` was not stamped. The plan sent 12 candidate lessons and no `lessons-capture` residue beyond them.

## Routing and Merge Behavior

- Review: CodeRabbit (required) and Sourcery. Two hardening findings were fixed (system git config loading, HTTP redirect targets), one declined with a documented reason (`http.extraHeader` / `http.cookieFile`, whose application in JGit nobody verified). **Provider re-read:** `review-gate` for #25 returns `undecidable_read` (the reviewer's body section "Minor comments: 18" is unreadable by the parser), so `unresolved_review_threads` and `review_body_findings_total` could not be re-read, and the landing's `unknown` stays `unknown`: not a difference. `review_body_findings_handled` is not re-verified (the dispositions live in the run's progress file, which was not read).
- CI/merge: merged through the merge queue as a squash, `2c728bc`, after PLAN-18's `7418f76`; no rebase conflict reported.
- Sonar: the landing claims `sonar_open_new_issues=0`. This analysis reads the quality gate of #25 as `OK` (new coverage 86.4 %, no new-code rating above A, hotspots 100 % reviewed). The `search` verb lists 14 issues for the pull request without a status column, so an open count of 0 is **not re-verified** (the listed issues include ones the plan fixed). No `step.sonar-roundtrip.open_issue_rules` came with the landing.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-02 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-02 --field pr --value 25`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-02 --field landing --value landings/PLAN-02.md`
- [x] row `plan_marshall_plan_id` stamped — `orchestrator queue --set-row PLAN-02 --field plan_marshall_plan_id --value git-core-import`
- [x] epic.md narrative reconciled (queue annotations, decisions, defects, watches)
- [x] layer-1 verdicts persisted onto the spec (claims 6, 7, 8 corroborated; 9 unverifiable)
- [x] undeclared surface of PLAN-03..17 corrected in the same act as the two lessons about the module-state statement (`doc/README.adoc`, `README.adoc`, `AGENTS.md`, `doc/concepts/module-structure.adoc`)
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- **Layer-3 defects and the four residuals of message `git-core-import-012`** (the process start, the end-to-end redirect test, the remote-side hook, the unverified `http.extraHeader` / `http.cookieFile` claim) were reported to the operator at once, who chose to fold them into the staged git-core plans: the code-side items (L3-1 to L3-9, residual 1 and the wording of the "implemented" claim) into **PLAN-04** as deliverable 5; the test-side items (L2-1 to L2-4, residuals 2 to 4, the untested branches) into **PLAN-05** as deliverable 5. Both specs carry the matching Expected Surface entries and OBSERVED claims.
- Incomplete landing: `inbox landing-check` reports `complete: false`, missing `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`. Recorded as an Open Defect (second one of the same kind, see epic.md).
- Nine new tooling lessons were filed (`2026-11-18-13-001` to `-009`, merging this plan's and PLAN-18's repeated findings); two inbox messages repeat the documentation under-coverage lesson `2026-11-18-07-001`, which was extended with the recurrence, and PLAN-18's keyless dispatch-boundary message extended `2026-11-18-07-006`.
- PLAN-03 and PLAN-04 are now both startable; their declared surfaces share `AGENTS.md` and `doc/README.adoc`, so the gate will ask the operator (multi-file overlap).
