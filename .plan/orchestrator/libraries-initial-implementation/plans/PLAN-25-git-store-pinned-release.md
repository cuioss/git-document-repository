# PLAN-25: git-store — the pinned release, the retry and the leftovers of PLAN-13 and PLAN-22

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fix the defects, test gaps and documentation gaps that the three-layer analyses of PLAN-13 (`landings/PLAN-13.md`, PR #64, `e38e079`) and PLAN-22
(`landings/PLAN-22.md`, PR #76, `c22700a`) found in `git-store`, and that no staged plan owns. This is the container plan of the epic for `git-store`
(PLAN-22, the first one, has shipped): it was staged on 2026-11-24 under the epic's routing rule and the operator's answer to the fork of PLAN-13's
analysis, and re-grounded on 2026-11-25 at `c22700a`. **Held back from `next` until the operator releases it.** Later `git-store` leftovers of
landing analyses fold into this spec until it is launched.

## Deliverables

1. The pinned release on the tag-only recovery path (`landings/PLAN-13.md` L3-2, `landings/PLAN-22.md` L3-1), first re-read at the plan's head and
   reproduced by a test that fails at `c22700a` (lines are those of `c22700a`): **`DocumentRelease.attempt` (281-288) takes the tag-only recovery
   path whenever `mergedRelease` finds an earlier merged release of the same version whose second parent is an ancestor of the branch tip, and it
   never consults the pin before `tagAndPush`; the pin is compared only in `checkedAndWritten` (307-311), which runs after the recovery.** A release A
   that left `main` merged with its tag missing (`INCOMPLETE`), a later save B and a pinned release of B end with the tag on A's release commit and an
   `OK` `Release` that names A, now with `branchMovedOn=true` (PLAN-22 made the case visible; the Javadoc 104-112 says the recovery "publishes nothing
   new"). The consumer `git-forms` ignored the flag until PLAN-14 (shipped 2026-11-27, `0187cf3`): `TransitionSave` 158-160 now maps it to `SAVED_NOT_RELEASED`, so a
   transition no longer reports `Performed` for a state that was not released; the store behaviour below is still the open decision. Decide at the outline, with the owner because it changes the documented behaviour of GDR-STORE-6, between: the pinned
   release refuses the recovery path when the pin is not the commit the earlier release merged (a passed-on `CONFLICT`, nothing written, so the
   caller's retry re-reads and releases B); it completes A's tag and then releases B in the same call; or it keeps the `OK` with the flag and the
   result names both commits. Record the decision in `git-store.adoc` § GDR-STORE-6 and `general.adoc`'s outcome table if an outcome changes. Tests:
   the sequence above through `ReleaseTest`, the case without a pin (unchanged), and a pin equal to A's merged commit (tags A, returns A).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
2. The leftovers of PLAN-22's landing (`landings/PLAN-22.md` L3-4 to L3-7, L3-9, L3-10, L2-2, L2-3), each first re-read at the plan's head, behaviour
   fixes reproduced by a test that fails at `c22700a` (lines are those of `c22700a`): (a) **a stale remote lock now retries without a pause**:
   `JGitRepositoryOperations` (200, 1010) answers `failed to lock` for a stale or permanent `.lock` file too, so `ExplicitSave` (402-406) and
   `DocumentRelease.attempts` (240-256) retry up to `save.max-attempts` (at most 100) fetch-and-push rounds under the transport lock with no pause:
   add a bounded pause or backoff between attempts that lost a lease, tested with a scripted remote that refuses every push (the classification itself is
   `git-core`'s, PLAN-24 deliverable 3); (b) the local release tag and commit stay after the last lost-lease attempt (`DocumentRelease` 243-253, 354-359,
   documented at 119-120), so `StoreIndex.snapshot("<collection>/<id>/v<n>")` can resolve a commit that was never released: decide whether the
   snapshot refuses a local-only release tag or the tag is removed on exhaustion, and record it; (c) a draft tree that holds a gitlink (mode 160000)
   written by a foreign tool ends the save as `FAILED` (`ExplicitSave` 760-768 passes it to `DocumentRelease.mode` and reads a commit id as a blob),
   the defect release now refuses as `MALFORMED`: refuse it as `MALFORMED` in the draft save too, tested; (d) `expireDrafts(Instant.MAX)` throws a
   `DateTimeException` out of the public method (`DraftExpiry` 139-144, GDR-GEN-3): return a result, and check the period against the caller's `now`,
   tested; (e) the Javadoc of `SignatureVerification` (70-76) claims the memory of the verification does not grow with the history and it is O(commits)
   (only the bodies are bounded): correct the claim or bound the walk; (f) debug records of `DocumentRelease` (248, 284, 309), `ExplicitSave` (402) and
   `DraftReferences` (246) name `request.source()`, a reference that contains the document id, against the plan's own review lesson: log the alias and
   the outcome, tested with the JUL capture; (g) `ConcurrentWritersTest` (89-101, 296-310) raised `ATTEMPTS` to 28, so the attempt boundary of item
   2(g) of PLAN-22 is untested and "no writer needed more than ATTEMPTS" cannot fail: add the case of a writer that loses nine times at
   `save.max-attempts` 10 and ends `Saved`, and one that runs out; (h) the visible skip of the cross-checks fails only under
   `GIT_STORE_REQUIRE_CROSS_CHECKS=true` (`ExternalTools` 50-117) and nothing outside `git-store/src` sets it: report in the PR whether the CI workflow can
   set it (the workflow files are the owner's; do not edit `.github/`), and record the answer in `git-store.adoc`; (i) `AGENTS.md` "Current state" does
   not say that `SaveStep.Attempt.mode` is abstract and so breaks an external implementor: add the sentence; (j) `StoreClone.close` (137-149) takes only
   the anchoring lock and returns normally from the documented refusal inside a callback with the writer still open: state in the Javadoc and
   `git-store.adoc` what `close` waits for and what it does not.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core
   - Persona: implementer

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked`
  packages with `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc
  on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock
  against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail,
  exception message, log message or commit metadata.
- Verify every claim about a JGit API against JGit 7.8.1 source or documentation and name the version (CLAUDE.md).
- Every behaviour fix is first reproduced by a test that fails at `c22700a` and passes at the plan's head; an item whose premise the re-read at outline
  refutes is dropped from the deliverable that names it (the unbounded blob reads staged here on 2026-11-25 were refuted by PLAN-22 and dropped).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn`
  ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit, `cui-test-generator`, JUnit 5, EasyMock,
  `cui-test-juli-logger`, Lombok, `cui-java-tools`, JOL (test scope of `git-store`). Any other library is put to the owner
  through the question tool BEFORE it is added.
- `git-core` and the aspect modules other than `git-store` stay read-only; an item that needs a change there is reported in the PR and the inbox
  message, not absorbed (the classification of a lost lease is PLAN-24's). A change of the public signature of `StoreWriter.release` is a `git-forms`
  break and is put to the owner first.
- Do not touch `.github/project.yml` or any workflow file (every PR that touches the first starts the release workflow; AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S
  SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The
  lane never records a dismissal of its own; a dismissal the run cannot transmit is a blocker. The merge gate reads the
  open-finding count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks. The three S4449
  suppressions in `StoreConfiguration` (289, 309, 344) carry a reason and no `operator-selected` record: put them to the operator.
- Review lessons of PLAN-13 and PLAN-22: a read-modify-write of a reference is a compare-and-swap on the id that was read; a release or tag is pinned to
  the commit that was validated; a bound holds on the write side as well as the read side; list every public entry point and decide its re-entry from a
  callback that holds a lock in one guard seam; a cache key includes every input of the cached result; sync with `origin/main` before every push of a fix round.

## Done when

- `verify` passes at the plan's head, and every behaviour fix of deliverables 1 and 2 has a test that fails at `c22700a` (`main` after PLAN-22, where the
  defects are present) and passes at the plan's head.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is
  `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the pinned release does not consult the pin on the tag-only recovery path — read at
  `git-store/src/main/java/de/cuioss/git/document/store/DocumentRelease.java` 281-288 (the `mergedRelease` result goes to `tagAndPush` with
  `movedOn(...)` and no pin comparison) and 307-311 (the pin check, in `checkedAndWritten`, reached only when no merged release was found) at `c22700a`.
  - verdict: corroborated | checked_at: 0187cf3df10512aa71b2a19ea950c11654588303 | by: libraries-initial-implementation/cleanup | rescoped: n/a | evidence: re-read at 0187cf3: DocumentRelease.java is unchanged since c22700a (PLAN-14 changed only StoreIndex in git-store main), so the recovery path still reaches tagAndPush without a pin comparison
- OBSERVED: `git-forms` does not read `Release.branchMovedOn` — a grep of `git-forms/src` for the name is empty, and `TransitionSave` 124-136 returns
  `Performed` for any `OK` at `c22700a`.
  - verdict: contradicted | checked_at: 0187cf3df10512aa71b2a19ea950c11654588303 | by: libraries-initial-implementation/cleanup | rescoped: yes | evidence: re-read at 0187cf3: git-forms now reads Release.branchMovedOn, TransitionSave.java 158-160 returns savedNotReleased() (PLAN-14 d7, PR #81); the spec states the old reading as history and keeps the store decision open
- OBSERVED: a stale remote lock is classified as a lost lease and retried without a pause — `JGitRepositoryOperations` 196-212 and 1008-1014 (the message
  `failed to lock`) and `DocumentRelease.attempts` 240-256 (the loop over `attempts` with no pause between the attempts) at `c22700a`.
  - verdict: corroborated | checked_at: 0187cf3df10512aa71b2a19ea950c11654588303 | by: libraries-initial-implementation/cleanup | rescoped: n/a | evidence: re-read at 0187cf3: git-core is untouched by PR #81; JGitRepositoryOperations.java 200 still names the remote message failed to lock as the lost-lease classification, DocumentRelease unchanged

## Expected Surface

- OBSERVED: `git-store/src/main/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/src/test/java/de/cuioss/git/document/store/`
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/specification/general.adoc` (only if an outcome or its detail changes)
- HYPOTHESIS: `git-store/doc/LogMessages.adoc` (only if a log message is added)

## Dependencies and Sequencing

- Depends on: PLAN-13 (shipped, PR #64, `e38e079`), which added the pinned release, and PLAN-22 (shipped, PR #76, `c22700a`).
- Overlaps with: PLAN-14 deliverable 7 (shipped; `git-forms` reads `branchMovedOn` and resumes a failed release through the pinned release; it relies on the
  behaviour this plan decides for the recovery path), PLAN-24 deliverable 3 (the classification of a lost lease in `git-core`, which deliverable 2(a)
  complements), and PLAN-26 deliverable 2(e) (the provider's own proof bound). `AGENTS.md`, `git-store.adoc` and `general.adoc` are shared with the
  other staged plans (strictly sequential per document).
- Adjacent to: PLAN-14 and PLAN-27 (`git-forms`).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-25-git-store-pinned-release.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
