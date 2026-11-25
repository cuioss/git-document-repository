# PLAN-25: git-store — the pinned release on the tag-only recovery path (the leftovers of PLAN-13)

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fix the defect that the three-layer analysis of PLAN-13 (`landings/PLAN-13.md`, PR #64, `e38e079`) found in the pinned release
that PLAN-13 added to `git-store` (`StoreWriter.release(author, claims, key, expectedCommitId)`, GDR-STORE-6), and that no
staged plan owns. This is the second container plan of the epic for `git-store` (PLAN-22 is the first and is running, so its
spec is frozen): it was staged on 2026-11-24 under the epic's routing rule and the operator's answer to the fork of PLAN-13's
analysis, **held back from `next` until the operator releases it**. Later `git-store` leftovers of landing analyses fold into
this spec until it is launched. It is strictly sequential with PLAN-22 on `git-store/` (both edit `DocumentRelease`).

## Deliverables

1. The tag-only recovery path of the pinned release (`landings/PLAN-13.md` L3-2), first re-read at the plan's head and
   reproduced by a test that fails at `e38e079` (lines are those of `e38e079`): **`DocumentRelease.attempts` (259-265) takes the
   tag-only recovery path whenever `mergedRelease` (458-470, `releaseShaped` 523-535) finds an earlier merged release of the
   same version whose second parent is an ancestor of the branch tip, and it never consults the pin before `tagAndPush`.** A
   release A that left `main` merged with its tag missing (`INCOMPLETE`), a later save B and a pinned release of B end with the
   tag on A's release commit and an `OK` `Release` that names A, while B is unreleased; `TransitionSave.release`
   (`git-forms`, 129-135) reports success. Decide at the outline, with the owner because it changes the documented behaviour of
   GDR-STORE-6 (the Javadoc of the path says it "publishes nothing new"), between: the pinned release refuses the recovery path
   when the pin is not the commit the earlier release merged (a passed-on `CONFLICT`, nothing written, so the caller's retry
   re-reads and releases B), or it completes A's tag and then releases B in the same call; record the decision in
   `git-store.adoc` § GDR-STORE-6 and `general.adoc`'s outcome table if an outcome changes. Tests: the sequence above through
   `ReleaseTest` (the existing test at 1012 pins only that the tag-only path is not refused), the case without a pin (unchanged),
   and a pin equal to A's merged commit (tags A, returns A).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
2. The unbounded read of a stored anchor proof (`landings/PLAN-17.md` L3-5, folded in on 2026-11-25 under the routing rule because
   this is the container plan for `git-store/`), first re-read at the plan's head and reproduced by a test that fails at
   `729ca28` (lines are those of `729ca28`): **`AnchorNotes` reads a note blob and a kept proof whole with
   `getBytes(Integer.MAX_VALUE)` (264 and 324)**, and every provider's `verify` receives the whole proof, so a very large note
   pushed to the notes reference makes the CI verification (`VerifyAnchors`) allocate it whole before any provider can refuse it
   (the provider of `git-timestamp` adds its own bound in PLAN-26, but the allocation has already happened). Bound the read to a
   documented size (decide the bound at the outline, with the owner because it is a new limit of GDR-STORE-15 to -18, and name it in
   `git-store.adoc`), check the size from the loader before reading as `OutputCheck` does (`ObjectLoader.getSize()`, JGit 7.8.1), and
   return a result for a proof over the bound: a note that exceeds it is reported by the verification, not thrown, tested with a
   note one byte over the bound for the note path and for the kept-proof path.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked`
  packages with `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc
  on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock
  against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail,
  exception message, log message or commit metadata.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Every behaviour fix is first reproduced by a test that fails at `e38e079` and passes at the plan's head; an item whose premise
  the re-read at outline refutes is dropped from the deliverable that names it.
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn`
  ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit 7.8.0, `cui-test-generator`, JUnit 5, EasyMock,
  `cui-test-juli-logger`, Lombok, `cui-java-tools`, JOL (test scope of `git-store`). Any other library is put to the owner
  through the question tool BEFORE it is added.
- `git-core` and the aspect modules other than `git-store` stay read-only; an item that needs a change there is reported in the
  PR and the inbox message, not absorbed. A change of the public signature of `StoreWriter.release` is a `git-forms` break and
  is put to the owner first.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S
  SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The
  lane never records a dismissal of its own; a dismissal the run cannot transmit is a blocker. The merge gate reads the
  open-finding count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks.
- Review lessons of PLAN-13 (`landings/PLAN-13.md`): a read-modify-write of a reference is a compare-and-swap on the id that was
  read; a release or tag is pinned to the commit that was validated; sync with `origin/main` before every push of a fix round.

## Done when

- `verify` passes at the plan's head, and the behaviour fix of deliverable 1 has a test that fails at `e38e079` (`main` after
  PLAN-13, where the defect is present) and that of deliverable 2 one that fails at `729ca28` (`main` after PLAN-17), and both pass at the plan's head; an item whose premise the re-read at outline refutes is
  dropped.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is
  `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the pinned release does not consult the pin on the tag-only recovery path — read at
  `git-store/src/main/java/de/cuioss/git/document/store/DocumentRelease.java` 259-265 (`mergedRelease` result goes to `tagAndPush`
  with no pin comparison), 458-470 and 523-535 at `e38e079`.
- OBSERVED: `AnchorNotes` reads a stored proof whole — `getBytes(Integer.MAX_VALUE)` at
  `git-store/src/main/java/de/cuioss/git/document/store/AnchorNotes.java` 264 (the note path) and 324 (the kept-proof path) at `729ca28`.
  - verdict: corroborated | checked_at: 729ca28f8b670943d5b425ffa96934f7fda9f976 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 729ca28: AnchorNotes 262-266 returns decode(reader.open(...).getBytes(Integer.MAX_VALUE)) for the one added blob and 320-325 reads the kept proof the same way

## Expected Surface

- OBSERVED: `git-store/src/main/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/src/test/java/de/cuioss/git/document/store/`
- OBSERVED: `doc/specification/git-store.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (only if an outcome or its detail changes)
- HYPOTHESIS: `git-store/doc/LogMessages.adoc` (only if a log message is added)
- HYPOTHESIS: `AGENTS.md` ("Current state": only if a public type or method changes)

## Dependencies and Sequencing

- Depends on: PLAN-13 (shipped, PR #64, `e38e079`), which added the pinned release.
- Overlaps with: PLAN-22 (running; `git-store/`, `AGENTS.md`, `general.adoc`, `git-store.adoc`) — strictly sequential after it, and
  PLAN-22's PR (#76) must be merged first so the rebase is not repeated.
- Adjacent to: PLAN-14 deliverable 7 (`git-forms` resumes a failed release through the pinned release; it relies on the behaviour
  this plan decides for the recovery path).

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
