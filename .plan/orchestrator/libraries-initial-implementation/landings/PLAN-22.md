# Landing Analysis: PLAN-22 — git-store hardening (the leftovers of PLAN-07 to PLAN-11)

epic: libraries-initial-implementation
workstream: WS-02
pr: #76 (https://github.com/cuioss/git-document-repository/pull/76), squash-merged through the merge queue as `c22700ad81fd222b00887fc39cd98428b0392a40` on 2026-11-25 (PR #75, the first PR of the branch, was closed by the review-bot rate-limit recovery and re-opened as #76)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-25 from the landing message
> `plan-22-store-hardening-024` (the plan's inbox message), corroborated against git, the PR through the CI abstraction and the merged
> code. The message is a lead, not a fact; what follows states what was checked.

## Deliverable Fidelity vs Spec

The spec staged four deliverables and the lane reported four (`deliverables_total=4`, `deliverables_done=4`). All four trace to code and to
tests. The spec's two standing constraints that did not survive are recorded below: `git-core` was to stay read-only, and `git-store`'s public API
was not to change; both were widened by operator decisions during the run (TASK-3 item b "Change git-core", TASK-3 item h "Add a parameter", and a
later triage answer, TASK-13).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. `git-store` leftovers of PLAN-07 (token in a remote's query, a throwing `id()`, eager providers, textual key check, test residues) | shipped-as-specified | `StoreRepository` 66-170 renders scheme, host, port and path only (user, password, query and fragment cut); `StoreProviders` reads the provider id once per selection; tests in `RepositoryRegistryTest`, `StoreProvidersTest` |
| 2. leftovers of PLAN-08 (bounded guard maps, a permanent rejection retried up to the bound, the retry pause, gitlink, tests) | shipped-modified | `RepositoryGuard` 64-68, 208-219 (one entry per save in flight, a holders count changed inside `compute`); the permanent-rejection fix needed a **`git-core` change** (the lost lease told from other failures by the remote's message, `JGitRepositoryOperations` 200-210, 960-962, 1010-1013); item 2(g) (nine losses at ten attempts) was overtaken by raising `ATTEMPTS` in `ConcurrentWritersTest`; the gitlink item was done for release only (`DocumentRelease` refuses a gitlink as `MALFORMED`), not for the draft save |
| 3. leftovers of PLAN-09 (13 items: release, index, listeners, the save step) | shipped-as-specified, one point shipped-modified | `Release` gained the record component `branchMovedOn`, `StoreWriter.open` gained `collections` (both old overloads gone), `SaveStep.Attempt` gained the abstract `mode`, `IdentityClaims.of` was added; `AGENTS.md` and `git-store.adoc` agree with the new API; the tag-only recovery path now reports `branchMovedOn` instead of staying silent (see L3-1) |
| 4. leftovers of PLAN-10 and PLAN-11 (signature and anchor verifiers) | shipped-as-specified | `AnchorVerification` 237-250, 403-432 (the checkpoint chain: `CHECKPOINT_DUPLICATE`, `CHECKPOINT_CHAIN_BROKEN`), `SignatureVerification` 285-289 (more than one `gpgsig` header is `INVALID`), `AllowedSigners` 148-210 (a bounded read), `ExplicitSave.readBlob` 175-226 (one 16 MiB bound for every blob read); tests `AnchorVerificationTest`, `SignatureVerificationTest`, `SignatureVerificationOversizedTest`, `ExplicitSaveBlobBoundTest` |
| (spec constraint) `git-core` read-only; no change of the public API | **shipped-modified** | `git-core` main changed (`JGitRepositoryOperations`, `RepositoryOperations`, `GitOutcome` Javadoc only, no new constant, a test helper `RefusingReceiveProtocol`, `git-core.adoc`, `general.adoc`, `pm-mcp-core.adoc`) on the operator's decisions; the decision is recorded in `git-core.adoc` 520-527 |

Added-unplanned, not in the spec: `CloneWrites`, `CloneSchedules` and `CloneOpening` (the clone's callback and lock seams), the `ThreadStates` test
helper and `git-forms` `CloneReader` (a `SaveStep.Attempt.mode` implementation kept compiling).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`) at `c22700a`, by reading only, with
no build, no test run and no write. The orchestrator re-read the points that carry a major or minor finding and the claim about the closed unbounded
reads at their `file:line` before writing them here: `DocumentRelease` 104-112, 240-256, 278-312, `TransitionSave` 124-136, the grep for
`branchMovedOn` in `git-forms`, `JGitRepositoryOperations` 196-212 and 1008-1014, `ExplicitSave` 760-768, `DraftExpiry` 136-146, `CloneSchedules`
146-162, `RepositoryGuard` 62-70, the grep for `GIT_STORE_REQUIRE_CROSS_CHECKS` outside `git-store/src`, the grep of `git-forms/src/test` for
`TreeEdit.Mode`, and the grep for `Integer.MAX_VALUE` in `git-store` main. Points that rest on the leaf alone and were NOT re-read: the `AnchorVerification`
cost, the `StoreClone.close` and `SignatureVerification` Javadoc points, the `AllowedSigners` TOCTOU, the `StoreRepository.toString` branches, the
`DocumentRelease` first-parent walk, the log-hygiene debug records and the conformance rows.

- **Dispatch:** one read-only leaf per landing — reason: a 4-deliverable hardening of the store with new public API and a `git-core` change is the
  depth the dispatch rule exists for, and the read must not share context with the PLAN-13 and PLAN-17 analyses.
- **Verdict:** layer 1 findings (1 minor); layer 2 findings (3 minor); layer 3 findings (**2 major**, 9 minor, 5 info that confirm).

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | the spec conflicts with itself: `git-core` read-only and one reference per push, against item 2(b), which was met by a `git-core` change on an operator decision recorded outside the spec body; premise 2(g) (nine losses, ten attempts) was overtaken by the same change | checked | refutes | `git-store.adoc` 336, 480; `git-core.adoc` 520-527 |
| L1-2 | the Done-when conditions otherwise agree with each other and with `main`; "`verify` passes" and the open Sonar count cannot be established by reading | not_shown_by_reading | none | — |

**Layer 1 verdict:** findings — 1 (L1-1), not shown by reading — 1 (L1-2).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1, 3 | `StoreRepository`, `StoreProviders`, `Release`, `StoreWriter` 103-162 | `RepositoryRegistryTest`, `StoreProvidersTest`, `ReleaseTest`, `SaveStepTest` | `git-forms` `CloneReader` 190-212 (compile only) | checked |
| 2 | `RepositoryGuard`, `JGitRepositoryOperations` | `JGitRepositoryOperationsTest` 875-990 (a real lock file, an injected native wording, near-miss wordings, `NONFASTFORWARD`, `NODELETE`; they can fail; the 2.56.1 claim is injected text, not a native process), `ConcurrentWritersTest` | see L2-2 | checked |
| 4 | `AnchorVerification`, `SignatureVerification`, `AllowedSigners`, `ExplicitSave.readBlob` | `AnchorVerificationTest`, `SignatureVerificationTest`, `ExplicitSaveBlobBoundTest` | none | checked |

| Finding | Claim | Severity | Evidence |
|---------|-------|----------|----------|
| L2-1 | the new `SaveStep.Attempt.mode` implementation for the saved-commit view in `git-forms` has no test (a grep of `git-forms/src/test` for `TreeEdit.Mode` is empty) | minor | `CloneReader.java` 190-212 |
| L2-2 | item 2(g) asks that a writer losing nine times still ends `Saved` at `save.max-attempts` 10; the landing raised `ATTEMPTS` to 28, so the attempt boundary is untested and "no writer needed more than ATTEMPTS" cannot fail | minor | `ConcurrentWritersTest.java` 89-101, 296-310 |
| L2-3 | the visible skip of the cross-checks fails only under `GIT_STORE_REQUIRE_CROSS_CHECKS=true`, and nothing outside `git-store/src` sets it, so a green CI build does not prove the `git` and `ssh-keygen` cross-checks ran | minor | `ExternalTools.java` 50-117 |

**Layer 2 verdict:** findings — 3 (L2-1 to L2-3), none blocking. No test was run, so "can fail" is read from the assertions.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `DocumentRelease.java` 281-288 (pin check at 307-311); Javadoc 104-112 | a release pinned to a newer saved commit C2 after an earlier release of C1 left `main` merged without its tag | the tag-only recovery runs before the pin check, completes C1's tag and returns `OK` with `Release.branchMovedOn=true`; the result does not hold the pinned commit; the Javadoc says this on purpose ("publishes nothing new"); at `e38e079` the path was at 259-265 and returned silently, so PLAN-22 made it visible but not safe | **major** |
| L3-2 | `TransitionSave.java` 124-136; `DocumentTransitions.java` 64 (`git-forms`) | the same sequence reached through a `release` transition | `git-forms` returns `Performed(release.commitId(), release)` for any `OK` and never reads `branchMovedOn` (a grep of `git-forms/src` is empty); its Javadoc says the release "publishes exactly that commit"; a transition reports `Performed` although its saved state was not released | **major** |
| L3-3 | `JGitRepositoryOperations.java` 200-210, 960-962, 1010-1013 | a remote that words a lost race otherwise ("failed to update ref", a hosted remote), or a native Git older than 2.56.1 | a lost lease is told from other failures by the exact text `failed to lock` (JGit `ReceivePack`) or `incorrect old value provided` (native Git); any other wording is a permanent `REJECTED`, so `ExplicitSave` stops where it used to retry; recorded as an accepted limit (owner decision of 2026-11-24, `git-core.adoc` 520-527); verified on Git 2.56.1 only | **major** (accepted limit) |
| L3-4 | `JGitRepositoryOperations.java` 200, 1010; `ExplicitSave` 402-406; `DocumentRelease.attempts` 240-256 | a stale or permanent `.lock` file on the remote (the new test creates exactly that) | `failed to lock` is also what JGit answers for it, so it is now `LEASE_REFUSED` and the save and the release retry up to `save.max-attempts` (at most 100) fetch-and-push rounds under the transport lock with no pause: the retry storm of spec item 2(b), now limited to this cause | minor |
| L3-5 | `DocumentRelease.java` 243-253, 354-359 | the push of `main` loses its lease on the last attempt | the local release tag and commit stay (documented, Javadoc 119-120), so `StoreIndex.snapshot("<collection>/<id>/v<n>")` can resolve a commit that was never released until a pruning fetch; `deleteTag` runs only for non-lease failures | minor |
| L3-6 | `ExplicitSave.java` 760-768 | a draft tree that holds a gitlink (mode 160000) written by a foreign tool | `DocumentRelease.mode` maps it to a regular file and `readBlob` reads a commit id, so the save ends `FAILED`: the defect release item 3(c) now refuses as `MALFORMED`; `DocumentRelease.mode` is documented as never receiving a gitlink | minor |
| L3-7 | `DraftExpiry.java` 139-144 | `expireDrafts(Instant.MAX)` or an extreme `now` | `now.atOffset(UTC).minus(expiry)` throws `DateTimeException` out of the public `StoreWriter.expireDrafts` (GDR-GEN-3); the configuration check tests the period against the epoch, not against the caller's `now` | minor |
| L3-8 | `SignatureVerification.java` 70-76, 258-276 | a long history | the Javadoc claims the memory of the verification does not grow with the history; the `RevWalk` pool keeps one `RevCommit` shell and its parents array per commit plus the seen set and the queue, so memory is O(commits) (only the bodies are bounded) | minor |
| L3-9 | `DocumentRelease.java` 248, 284, 309 (also `ExplicitSave` 402, `DraftReferences` 246) | debug logging on | debug records name `request.source()`, a reference that contains the document id, against the plan's own review lesson to log the alias and the outcome only; GDR-NFR-3 itself is not breached (a pseudonym and an id are no listed secret) | minor |
| L3-10 | `StoreClone.java` 137-149 | `close()` while a save, release or fetch runs on another thread; or `close()` inside a callback | `close()` takes only the anchoring lock and does not wait for a running operation; the documented refusal inside a callback returns normally with the writer still open, a silent non-close in try-with-resources | minor (documented in part) |
| L3-11 | `CloneSchedules.java` 148-161, 246-257 | an `OutOfMemoryError` in a scheduled fetch | the scheduled runner catches `Throwable` and logs a WARN, the opposite of the listener rule that a `VirtualMachineError` propagates; documented as the scheduled-runner rule with an operator-selected Sonar key | minor (documented) |
| L3-12 | `DocumentRelease.java` 262-296, 594-608 | the first release of a document in a long-history `main` | each attempt that finds no merged release walks the first-parent chain back to the previous release or the root, calling `parseBody` on each commit; cost grows with the history; predates the plan and no deliverable names it | minor |
| L3-13 | `AnchorVerification.java` 298-311 | many notes commits | `NoteMap.read` and a diff per notes commit: quadratic in notes commits times notes; a CI main class, so a cost, not a defect | minor |
| info | `AnchorNotes.java` 267, 327; `Checkpoints.java` 383; `AnchorVerification.java` 346 | — | **the unbounded `getBytes(Integer.MAX_VALUE)` reads of `729ca28` (264, 324) are now bounded**: all go through `ExplicitSave.readBlob` (`BLOB_LIMIT` 16 MiB, size asked first); re-read: no `Integer.MAX_VALUE` read is left in `git-store` main | info (confirms) |
| info | `StoreRepository.java` 66-170, `RepositoryGuard.java` 64-68 and 208-219, `AnchorVerification.java` 237-250 and 403-432, `AllowedSigners.java` 148-210, `SignatureVerification.java` 285-289 | — | confirms: the remote rendering, the bounded draft-lock map (the `GUARDS` map stays one entry per clone path, a recorded limit), the checkpoint chain, the bounded allowed-signers read and the fail-closed `gpgsig-sha256` | info (confirms) |

**Layer 3 not shown by reading:** whether `verify` and the 2,356 tests pass; whether each behaviour-fix test fails at `4a5eed5`; the open Sonar count at
the head (the three S4449 suppressions in `StoreConfiguration` 289, 309, 344 carry a reason and no `operator-selected` record, and the keys cannot be checked
in Sonar); the native-Git wording on any version other than 2.56.1 and on the reftable backend; the wording hosted remotes use for a lost race; the
OpenSSH `z` suffix claim, which cites an unpinned `master`; the head `09b57182`, which CodeRabbit never reviewed.

**Layer 3 verdict:** findings — 2 major (L3-1, L3-2) and one major that is an accepted limit (L3-3), 10 minor (L3-4 to L3-13); none a blocker.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | expected outcomes returned as results, not thrown | **deviates** | `DraftExpiry.java` 144 (L3-7); `IdentityClaims` still throws by design, the result form `IdentityClaims.of` was added |
| AGENTS.md | JPMS, `@NullMarked`, `CuiLogger` with a `LogRecord` holder and a catalogue, Javadoc on every public type and method | conforms | `GitStoreLogMessages`, `LogMessages.adoc` and `LogMessagesCatalogueTest` changed together; `Release`, `IdentityClaims.of`, `SaveStep.Attempt.mode`, `StoreWriter.open` carry updated Javadoc |
| AGENTS.md | tests JUnit 5, `cui-test-generator`, EasyMock | conforms | new tests use `Generators` and `ScriptedRepositoryOperations`; `RefusingReceiveProtocol` is a documented subclass |
| AGENTS.md | no dependency; `git-core` pulls nothing from higher modules | conforms | no `pom.xml` and no `.github/project.yml` in the diff stat |
| AGENTS.md | update "Current state" when a public type changes | conforms, one omission | the diff lists `open(collections)`, `Release.branchMovedOn`, `SaveStep.Attempt.mode`, `IdentityClaims.of`, the lease classification and the 16 MiB bound; it does not say that `SaveStep.Attempt.mode` is abstract and breaks an external implementor |
| GDR-NFR-3 | no secret in results, exceptions, logs or commit metadata | conforms | `StoreRepository.rendered` cuts user, password, query and fragment; catch blocks log the class name only; debug records name references (L3-9) |
| plan spec | verify every JGit claim against 7.8.0 and name the version | conforms | `JGitRepositoryOperations` 200-210 and 969-985 name JGit 7.8.0; the native wording is stated as observed with Git 2.56.1 only |
| plan spec | no new `GitOutcome` constant | conforms | the `GitOutcome.java` diff is Javadoc only |
| plan spec | `git-core` stays read-only | **deviates** | operator decisions (`git-core.adoc` 520-527) |
| plan spec | every behaviour fix has a test that fails at the base; Sonar findings fixed or operator-selected | not_shown_by_reading | needs a run against the base and the Sonar read |

## Metrics and Anomalies

- Tokens: 41,873,470 (`totals_tokens_population: mixed`; the 1-init row is unmeasured, so the figure is a floor).
- Duration: 84,465 s of wall clock (about 23.5 hours), 24 tasks: TASK-9 to TASK-24 were the fix tasks of six loop-backs.
- Anomalies: seven CodeRabbit review rounds, each finding new real defects of the same few classes (the isolation rule, re-entry from callbacks while a lock
  is held, bounds); a Sonar suppression round; PR #75 closed and re-opened as #76 by the rate-limit recovery, with the `create-pr` step record still naming
  #75; two source-tree commits refused in 6-finalize for a missing step id; an `orchestrator inbox detect` call without its required flag. All the tooling
  items are plan-marshall subjects and went to its inbox (`-099` to `-114`).

## Routing and Merge Behavior

- Review: CodeRabbit (required) paused itself after reviewing `c52acb44` and **never reviewed the final head `09b57182`** (the delta is TASK-23 and TASK-24:
  a deferred nested index move and a refusal of `snapshot` and `open` inside a clone callback); the barrier counted its earlier review as participation. Sourcery
  (optional) refused on diff size. Forty-six pr-comment findings and the walkthrough comment were answered. The three review numbers were re-read on 2026-11-25
  with `runbook_lane review-gate --pr-number 76`: the read is **undecidable** (`undecidable_read`: the parser cannot read CodeRabbit's "Minor comments: 1"),
  and the landing carries them as `unknown`, so they are missing keys, recorded as not re-verified.
- CI/merge: squash-merged through the merge queue; CI, the Sonar gate (0 new-code issues, the lane's claim) and the whole-tree `verify -Ppre-commit` (2,356
  tests) were green at the final head (the lane's claim). The squash message is the repository default and may carry PR-body text.
- Collision: PLAN-22 rebased over PLAN-13's pinned release and changed the same `DocumentRelease` and `StoreWriter`; the landing does not record a conflict.
  The Watch of 2026-11-25 is retired on that basis, with the remark that the landing says nothing about it.
- Sonar: the landing says `sonar_open_new_issues=0`; this analysis did not re-measure it (the Sonar project key is not configured locally).

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-22 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-22 --field pr --value 76`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-22 --field landing --value landings/PLAN-22.md`
- [x] row `plan_marshall_plan_id` already stamped (`plan-22-store-hardening`)
- [x] `epic.md` narrative reconciled: queue annotations, Decision, Open Defects (incomplete landing; the routed defects), the PLAN-22 rebase Watch retired
- [x] resume anchor updated; `queue-view.md` regenerated
- [x] sixteen candidate lessons moved to the plan-marshall `lessons-routing` inbox (`-099` to `-114`); six promoted into the local store (`2026-11-25-08-001` to `-006`); one discarded onto an open item; recorded in `lessons-moved.md`

## Follow-Ups

- **PLAN-25 deliverable 1** (the tag-only recovery path) is re-grounded at `c22700a`: the path moved to `DocumentRelease` 281-288 and now reports
  `branchMovedOn`, so the store-side question is whether an `OK` with the flag is the right result for a pinned release (L3-1). **PLAN-25 deliverable 2**
  (the unbounded read of a stored proof, staged from PLAN-17's finding L3-5) is **refuted and dropped**: PLAN-22 bounded every blob read. PLAN-25's new
  deliverable 2 takes L3-4 to L3-7 and L3-9, L2-2, L2-3 and the documentation point of L3-10.
- **PLAN-14 deliverable 7** gains item (h): `git-forms` reads `Release.branchMovedOn` (L3-2) and a test for `CloneReader.mode` (L2-1).
- **PLAN-24** (the `git-core` container) gains deliverable 3: whether a stale remote lock is told from a lost race, and the recorded limit of the exact
  wording (L3-3 and the git-core half of L3-4); decided with the owner at the outline because it changes a documented owner decision of 2026-11-24.
- **PLAN-26 deliverable 2(e)** is narrowed: the store now bounds every blob read and a provider proof at 16 MiB; the provider's own 1 MiB bound stays a
  consistency item.
- L3-8, L3-11, L3-12, L3-13 and the rest of L3-10 are recorded here and not routed (documented rules, predating costs, a CI main class).
