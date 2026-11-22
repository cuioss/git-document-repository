# PLAN-09: git-store — release, in-memory index, post-commit listeners, per-attempt save step

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Implement the read and publish side of the store: release by merging a working branch into `main` with a never-moved
tag `refs/tags/<collection>/<id>/v<n>` and the incomplete/repair path (GDR-STORE-6), the in-memory index fed by
consumer-registered contributors with periodic fetch and reporting-date snapshots (GDR-STORE-9) meeting the footprint
limit GDR-NFR-1, and in-process post-commit listeners (GDR-STORE-10). It also closes a gap in the write path PLAN-08 shipped: the save has no
per-attempt step, which the later plans of `git-forms` and `git-directory` need (deliverable 5, the operator's decision of
2026-11-21 on the landing analysis of PLAN-08).

## Deliverables

1. Release (GDR-STORE-6): merge working branch into `main` (path-level, entry path only for a numbered collection),
   tag `v<n>` counting from 1, `main` with lease and the tag pushed together, incomplete when `main` moved without
   the tag and a repeated release creating the missing tag instead of merging again, existing tags never moved or
   deleted, rejected for a refused push of `main`.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Index (GDR-STORE-9): index interface and per-collection contributors; built from `main` and every working and
   collection branch at start, after every fetch, save and release (only changed documents re-read after a save);
   periodic fetch at `fetch.interval`; `snapshotIndex(tag | commit)` without changing the current index.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
3. Listeners (GDR-STORE-10): subscriptions per branch, called oldest first after pushed saves/releases and after
   fetches that move a subscribed branch; a failing listener never fails the trigger; no listener progress kept.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Tests incl. GDR-NFR-1: 500 generated documents × 90 fields in an in-memory repository, index retained heap < 16 MB.
   The measuring tool is open in `doc/specification/general.adoc`; a tool that adds a dependency is put to the owner
   first, and the decision replaces "is open".
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester
5. Per-attempt save step (GDR-STORE-3 amended; consumed by GDR-FORMS-4 in PLAN-13 and GDR-DIR-3 in PLAN-16; the gap is
   recorded in `landings/PLAN-08.md`): `StoreWriter.save(Author, DocumentKey, Map, String)` (`StoreWriter.java` 229) takes
   no callback, and `ExplicitSave` re-applies only the draft's own edits on each attempt (232-239), while GDR-FORMS-4 needs
   generated output regenerated from the data of the commit the save creates, after every re-application onto a new tip,
   and GDR-DIR-3 needs a check against the tip it commits on in every attempt. Add an optional caller-supplied step to the
   save: it runs on every attempt, the first and each re-application, with read access to the tip the attempt commits on
   and to the tree the draft's edits produce, and returns either further edits, which become part of the same save commit
   (one commit, the tip as its only parent), or a refusal that ends the save with nothing pushed and draft and branch
   unchanged; a step that throws ends the save as a failed one (class name only, no lost-lease retry); it is given no
   credential and nothing the caller did not pass. Specify it in `doc/specification/git-store.adoc` § GDR-STORE-3 (the
   requirement is amended in place; requirement numbers stay without gap) and in the save sequence text; an outcome the
   refusal needs that no `SaveResult` or `StoreOutcome` constant covers is put to the owner through the question tool
   BEFORE a constant is added, as for any new outcome. Tests naming GDR-STORE-3: the step runs again against the new tip
   after a lease refusal, its edits land in the one commit whose only parent is the tip, a refusal pushes nothing and
   leaves draft and branch unchanged, a throwing step is a failed save, and GDR-NFR-3 holds for what the step receives and
   for its failure detail.
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc, pm-dev-java-cui:cui-logging
   - Persona: implementer

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, module name `de.cuioss.git.document.<suffix>` repeated as the
  automatic module name, `exports` added with the first public class of a package), `@NullMarked` packages with
  `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier
  (GDR-GEN-4), Javadoc on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 /
  `cui-test-generator` / EasyMock against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result
  detail, exception message, log message or commit metadata; tests assert it with a JUL log capture
  (`cui-test-juli-logger`) and with checks of result details.
- Verify every claim about a JGit API against JGit 7.8.0 source or documentation and name the version (CLAUDE.md).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call
  `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md). Approved on 2026-11-17: JGit 7.8.0, `cui-test-generator`,
  JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through
  the question tool BEFORE it is added, with the alternatives considered, and the spec document's "open" statement
  is replaced by the decision.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- When a module gains code, update its state in `doc/concepts/module-structure.adoc` (State column), the
  "Current state" paragraph of `AGENTS.md`, and the "planned / not yet implemented" wording of its specification
  document. When a module is created, add it to the root `pom.xml` `<modules>`, to the module table of `AGENTS.md`,
  and to the layering guard of PLAN-06 (GDR-GEN-1).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is a
  blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected;
  an `undecidable` read blocks.
- The release commit carries the releasing user's identity trailers once PLAN-10 lands; this plan leaves the hook
  point for them and does not invent a trailer format.
- `git-core` stays read-only. PLAN-08 edited it against its outline (`FetchInput.prune`, the public
  `JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS`, `git-core.adoc`); this plan reports a `git-core` gap in its PR and its
  inbox message instead of absorbing it.
- The save's fetch prunes (`FetchInput.prune` with `MIRROR_FETCH_REF_SPECS`, PLAN-08): it deletes every local reference
  below `refs/heads`, `refs/tags` and `refs/notes` whose source the remote does not advertise (JGit 7.8.0
  `FetchProcess.deleteStaleTrackingRefs`; `git-core.adoc` § fetch). A local tag created by a release is therefore lost to a
  pruning fetch that runs before its push: create and push it under the one transport lock of the clone
  (`RepositoryGuard`), or keep it out of the pruned set, and test the interleaving with a pause between the tag and its
  push.
- Review lessons of PLAN-08 (`landings/PLAN-08.md`): a green local build was followed by 18 valid bot findings and 33
  Sonar findings, mostly races. For every reference operation that reads and then writes or deletes, name the locks and
  the readers that sit between the check and the compare-and-swap and test each with a pause point; capture identifiers
  once and re-apply them on a retry; do not read a result code with several causes (`LOCK_FAILURE`) as one cause; derive
  remote state from the fetch result, never from mirror references; give a destructive option (prune) an explicit scope;
  after an irreversible remote effect report a later failure without contradicting it; detect a skipped operation from
  evidence that it ran, not from a pre-check (a TOCTOU guard is not a mechanism); a lock covers the reads its decision
  depends on; never attach a throwable to a log record on a path promised sanitised (DEBUG included, GDR-NFR-3); a test
  asserts content, takes fixture values that differ by construction, and ends a concurrent scenario with one deadline,
  cancellation and `shutdownNow` with `awaitTermination` (never `ExecutorService.close()` in try-with-resources); one
  throwing call per `assertThrows` lambda, no unused `throws`, and JGit returns (`getRef`, `resolve`, `getObjectId`)
  treated as `@Nullable` (Sonar S5778, S1130, S4449). Plan for a second review pass.

## Done when

- `verify` passes at the plan's head with GDR-STORE-3 (the per-attempt step), GDR-STORE-6/9/10 and GDR-NFR-1 each covered
  by a test naming the requirement id.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the release, index and listener contracts are specified in `doc/specification/git-store.adoc` §
  GDR-STORE-6, GDR-STORE-9, GDR-STORE-10; the index footprint limit in `doc/specification/general.adoc` § GDR-NFR-1.
  - verdict: corroborated | checked_at: 4a5eed5f593b4624f7b350358d289168ba1f9ae0 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at 4a5eed5: git-store.adoc GDR-STORE-6 (376-384), GDR-STORE-9 and GDR-STORE-10 keep their original bullets and gained bodies matching the code; general.adoc GDR-NFR-1 states the footprint limit; read by the three-layer leaf and re-read by the orchestrator (the GDR-STORE-6 bullet 'main and the tag must be pushed together' cannot be met by git-core, which pushes one reference per call: PLAN-22 deliverable 3 (m))
- HYPOTHESIS: the retained size of the index object graph cannot be measured reliably with the approved dependencies
  alone (a heap-walk tool such as JOL would be a new dependency) — confirm/refute at
  `doc/specification/general.adoc` § GDR-NFR-1 ("the measuring tool is open") and the approved list in the epic's
  Decisions at outline (verify-at-outline); confirmed → owner decision on a tool before the test is written.
  - verdict: corroborated | checked_at: 4a5eed5f593b4624f7b350358d289168ba1f9ae0 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: confirmed and settled by the owner at 4a5eed5: the approved dependencies cannot measure the retained size of the index graph; org.openjdk.jol:jol-core 0.17 was approved on 2026-11-21 as a test-scope dependency of git-store only (git-store/pom.xml, version managed in the root pom, jol.magicFieldOffset=true in Surefire); module-layering and consumer-isolation untouched
- OBSERVED: the shipped save has no per-attempt step — `StoreWriter.save(Author, DocumentKey, Map<String, String>, String)`
  takes no callback (`git-store/src/main/java/de/cuioss/git/document/store/StoreWriter.java` 229) and `ExplicitSave` loops
  `attempt <= attempts` over the draft captured once (232-239) — read at `c1e02fc`.
  - verdict: corroborated | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at c1e02fc: StoreWriter.java 229 save(Author, DocumentKey, Map<String,String>, String) has no callback parameter; ExplicitSave.java 232-239 loops attempt <= attempts over the draft captured once

## Expected Surface

- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/specification/general.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-store`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-08 (save, working branches, fetch; shipped, PR #48, `c1e02fc`, 2026-11-21: `StoreWriter` with `draft`,
  `drafts`, `save`, `expireDrafts`; `ExplicitSave`, `DraftExpiry`, `DraftReferences`, `RepositoryGuard` per clone path,
  `SaveResult`, `Author`, `TechnicalUser`, `Ulid`; `save.max-attempts` bounded at 100; `commit.email`).
- Five deliverables, under the scope-bloat guard (about six): deliverable 5 was folded in at the operator's word on
  2026-11-21 because the three later plans of `git-forms` and `git-directory` rest on it; the Expected Surface already
  declares `git-store/` and `git-store.adoc`, so it is unchanged. PLAN-13 and PLAN-16 wait for it.
- Overlaps with: every WS-02 plan — strictly sequential. With PLAN-12 (run side by side at the operator's word on
  2026-11-21) it shares `doc/specification/general.adoc`, `AGENTS.md` and `doc/README.adoc`: rebase onto whichever of the two
  merged first and keep both texts.
- Adjacent to: anchoring of release tags (GDR-STORE-16) — PLAN-11.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-09-store-release-index-listeners.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
