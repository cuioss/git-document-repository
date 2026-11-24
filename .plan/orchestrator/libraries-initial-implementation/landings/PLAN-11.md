# Landing Analysis: PLAN-11 — git-store: anchoring (subjects, checkpoints, proofs as notes, own timestamp `signed`)

epic: libraries-initial-implementation
workstream: WS-02
pr: #66 (https://github.com/cuioss/git-document-repository/pull/66), merged as `b30ec14` (first parent `af81382`), squash through the merge queue

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the lane's claims against git, the PR
> and the merged code. The landing message is `inbox/plan-11-store-anchoring-signed-012.md`; eleven candidate-lesson messages
> (001 to 011) came from the same plan.

## Deliverable Fidelity vs Spec

The spec numbers four deliverables and the landing facts agree (`deliverables_total=4, deliverables_done=4`). Evidence is the
merge diff `git diff af81382 b30ec14` (37 files, +8591 / -57, read through the PR record) and the leaf's reading of the merged code.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Anchoring engine (GDR-STORE-15/16) | shipped-as-specified | `AnchorEngine`, `Checkpoints`, the triggers in `StoreClone`, `AnchorSchedule`, `StoreWriter.anchor` and `anchorPeriodically`; `AnchorEngineTest`, `CheckpointsTest`, `AnchorInterleavingTest` |
| 2. Proofs as notes (GDR-STORE-17) | shipped-as-specified, with one wording deviation | `AnchorNotes`; `AnchorNotesTest`. "A local-only note is pushed, not re-requested" holds only while the notes tip equals the proof's basis: `AnchorEngine.pushKept` drops a kept proof and asks the provider again once the tip moved (`AnchorEngine.java:322-325`), which the chain requires (L1-3) |
| 3. Provider `signed` (GDR-STORE-18) | shipped-as-specified | `SignedAnchorProvider`, `AnchorStatement`, `StoreProviders.signed` (key separation by public-key content, `StoreProviders.java:281`), `AllowedSigners.fromAnchor`; `SignedAnchorProviderTest` with an `ssh-keygen -Y` cross-check behind `assumeTrue` |
| 4. Verification `verifyAnchors()` | shipped-modified | `AnchorVerification`, `VerifyAnchors`; `AnchorVerificationTest`, `VerifyAnchorsTest`. The deliverable says it "walks the checkpoint chain"; it checks missing numbers, every proof and the listed references, but not the parent link between checkpoints (L3-1, the one major) |
| (added) `AnchorProvider.obtain` gains `commitTime` | added, explained | the clock check of deliverable 3 needs the subject's commit time; a public SPI break the spec did not foresee (L1-2); the test implementer `TestAnchorProvider` follows it |
| (added) `module-structure.adoc`, `AGENTS.md`, `doc/README.adoc`, `git-store/doc/LogMessages.adoc` | added, explained | the Standing Constraints (state lists, GDR-GEN-4 catalogue); no pom, no `git-core`, no `.github/project.yml`, no new dependency |

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`), by reading only: no build, no test
run, no write. The orchestrator re-read every layer-3 point and the layer-1 and layer-2 points it records below at their lines
in `b30ec14` (git show) before recording them; points it did not re-read are marked "leaf-reported".

- **Dispatch:** one read-only leaf per landing — reason: reading 8,500 lines of merged anchoring code (locks, leases, compare-and-swap, signature verification) is the depth the dispatch rule exists for, and its context stays apart from the drain of the 33 messages of two landings.
- **Verdict:** layer 1: 2 minor, 2 info, 1 not shown; layer 2: 3 minor, 1 info, 1 not shown; layer 3: **1 major**, 11 minor, 2 info, no blocker (three of them an exception where the rules want a result) — see the sections.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | HYPOTHESIS "the SSHSIG armoring of PLAN-10 can sign arbitrary bytes under a custom namespace" | checked | confirms | `SshSignatures.signedData(namespace, message)` 107, `encode(blob, namespace, signature)` 145 and `verify(decoded, namespace, message)` 272 take the namespace as a parameter (re-read); persisted `corroborated` (claim 1). The OBSERVED format claim (claim 0) also holds, persisted `corroborated` |
| L1-2 | The spec treats `AnchorProvider` as given and rfc3161 as plugging in "without engine changes", but deliverable 3's clock check needs the subject's commit time | checked | refutes | `AnchorProvider.obtain(String, ObjectId, Instant commitTime, byte[])` (`AnchorProvider.java:68`, re-read): a public SPI break the spec did not foresee. PLAN-17 implements this SPI and must follow the new signature; its spec names the SPI (`AnchorProvider` with id `rfc3161`) but never the signature of `obtain` (grep of the spec body), so no correction of the spec is owed |
| L1-3 | "A local-only note is pushed, not re-requested" | checked | refutes | holds only while the tip is unchanged; the code re-requests when the tip moved (`AnchorEngine.java:322-325`, leaf-reported); the chain needs it, the absolute spec wording is wrong |
| L1-4 | Done-when "every GDR-STORE requirement is then covered" | checked | refutes | the landed specification itself says "Planned is GDR-STORE-19" (`PersonDirectory` provider, `git-directory`); the premise cannot hold (leaf-reported). The coverage clause for 15 to 18 holds |
| L1-5 | Expected Surface HYPOTHESIS "`git-store/` is a new module, not a top-level entry at HEAD" | checked | refutes | `git-store` existed at `af81382` since PLAN-07; the surface premise is stale. Not a Claim Label, so no verdict is persisted |
| L1-6 | Done-when "Sonar open-finding read at the head is 0 or operator-selected" | not_shown_by_reading | none | a remote fact; see Metrics |

**Layer 1 verdict:** findings — 2 minor (L1-2, L1-4), 2 info (L1-3, L1-5), not shown by reading — 1 (L1-6)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 | `AnchorEngine`, `Checkpoints`, `StoreClone`, `AnchorSchedule` | `AnchorEngineTest`, `CheckpointsTest`, `AnchorInterleavingTest` (the save paused inside the push hook, the tag and note survive) | none | checked (leaf-reported) |
| 2 | `AnchorNotes` | `AnchorNotesTest` | none | checked (leaf-reported) |
| 3 | `SignedAnchorProvider`, `AnchorStatement`, `StoreProviders.signed`, `AllowedSigners.fromAnchor` | `SignedAnchorProviderTest`, `AnchorStatementTest`, `StoreProvidersTest` (key separation 353) | none | checked (leaf-reported) |
| 4 | `AnchorVerification`, `VerifyAnchors` | `AnchorVerificationTest`, `VerifyAnchorsTest` | none | checked, with the verifier gaps of layer 3 |

| Finding | Claim | Verdict | Polarity |
|---------|-------|---------|----------|
| L2-1 | The three Sonar findings were closed by in-code `@SuppressWarnings` (`StoreClone.java:48` and `StoreWriter.java:84` for `java:S6539`, `StoreProviders.java:249` for `java:S4449`, each with a reason comment; re-read), where the spec's Sonar constraint prescribes a fix or a dismissal through `sonar_rest transition` on the operator's selection. The landing says the operator selected each key, but nothing in the repository shows it, and no transition is shown | checked | refutes (the prescribed path); the operator's selection is not shown by reading |
| L2-2 | `AnchorInterleavingTest` uses raw threads with a per-thread `join(timeout)` (`:283`, `:380`, re-read) and `assertFalse(isAlive)`: no single deadline, no cancellation, no `shutdownNow` with `awaitTermination`, as the standing constraint asks (the executor tests of `AnchorEngineTest` do follow it) | checked | refutes |
| L2-3 | The mirrored-set fix derives `LISTED_NAMESPACES` from `PUSH_REF_SPECS` (`StoreReferences.java:65`, re-read) but the prune scope is `JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS` in `git-core`; the Javadoc asserts equality and no test ties them, so a change in `git-core` drifts silently | checked | refutes |
| L2-4 | The review fixes the leaf could check hold: annotated tags peeled (`Checkpoints.peeled`, `RevWalk.parseCommit` in JGit 7.8.0), `RefUpdate` results checked on the create paths, schedule cancellation by a per-registration token, fetch updates dispatched on every normal return, the PLAN-10 seam kept (checkpoint and notes commits take `CommitSigning(signer, null, null)`, no trailers; `AnchorVerificationTest:416` verifies both) | checked | confirms |
| L2-5 | The lane's "16 findings fixed, Sonar 0" is not shown by the diff; the `ssh-keygen -Y` cross-check skips through `assumeTrue`, and that it ran in CI is not visible | not_shown_by_reading | none |

**Layer 2 verdict:** findings — 3 minor (L2-1, L2-2, L2-3), 1 info (L2-4), not shown by reading — 1 (L2-5); every deliverable traces to code and to a test that can fail

### Layer 3 — Correct

Re-read by the orchestrator at `b30ec14` unless marked leaf-reported.

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `AnchorVerification.java:185-210` (the checkpoint loop); no use of a checkpoint's parent anywhere in the class (grep: parents only in `firstParents` for the notes chain) | a remote administrator moves `refs/tags/checkpoints/3` onto checkpoint 4's commit, which already carries a note | `verifyAnchors` reports nothing: both numbers exist (no gap), the subject set is a `LinkedHashSet` so the duplicate collapses, no `NO_PROOF`, and checkpoint 3's listed references are no longer examined, so a branch rewrite between 3 and 4 goes unreported. The "never moved" invariant of GDR-STORE-16 is undetected and the report says clean. The deliverable says the verifier walks the chain | **major** |
| L3-2 | `AnchorVerification.java:327` (re-read); `Checkpoints.content` returns an empty array for a tree with no `refs` file (leaf-reported, `Checkpoints.java:384`) | a checkpoint commit with no `refs` file or garbage lines | zero findings for that checkpoint: a checkpoint the verifier could not read as listing anything is reported as clean | minor |
| L3-3 | `AnchorVerification.java:203`, `AnchorEngine.java:364` (the engine honours `anchor.releases`) | `anchor.releases=false`, then `VerifyAnchors` in CI | the verifier takes no configuration and treats every release-shaped tag as a subject: every release commit is `NO_PROOF` and the CI exit is 1; a release-shaped tag on a blob fails the whole verification while the engine skips it | minor |
| L3-4 | `AnchorVerification.java:339-352` (leaf-reported) | many checkpoints times many listed references | every checkpoint re-checks its whole `refs` file with one `RevWalk.isMergedInto` per line and no cache of an unchanged pair: quadratic CI time | minor |
| L3-5 | `AnchorNotes.java:264` (re-read), `:324` and `Checkpoints.java:387` (leaf-reported) | a note or `refs` blob of some gigabytes pushed by anyone with write access to `refs/notes/*` | `reader.open(...).getBytes(Integer.MAX_VALUE)` allocates it: an `OutOfMemoryError` the `catch RuntimeException` sites do not cover; the verifier's `getBytes()` at `AnchorVerification.java:298` has the JGit limit | minor |
| L3-6 | `AnchorEngine.java:160-161` (re-read: `guard.anchoring(...)` then `events.emit(updates)`, no `try`/`finally`) | any `RuntimeException` after the fetch of phase one (an unchecked JGit failure in `prepare`, which catches `IOException` only, or L3-7) | the collected fetch updates are lost; the fetch already moved the local refs, so a later fetch reports no change and `StoreIndex` and listeners never see those commits; `StoreWriter.anchor()` throws instead of returning a result (GDR-GEN-3) | minor |
| L3-7 | `Checkpoints.java:252` (re-read: `time.plus(interval)`); `SignedAnchorProvider.java:202` (re-read: `statement.time().plus(maxClockSkew)`) | a configured duration such as `P1000000000000D` (`ConfigurationTree.duration` accepts any positive duration) | `Instant.plus` throws `DateTimeException`: at `Checkpoints.java:252` it escapes `prepare` and `anchor()` as an unchecked exception; at `SignedAnchorProvider.java:202` the engine's catch turns it into `REFUSED` on every call, so the provider never signs | minor |
| L3-8 | `SignedAnchorProvider.java:222-225` (re-read) against `AnchorEngine.java:322-325` | the proof is signed and logged (INFO `GIT_STORE-7`, "every proof"), then the tip moves or the push fails and the kept proof is dropped | the log pipeline holds a proof (sequence n, one hash) that never reached Git, and its replacement carries the same sequence with another hash: the log copy is a forked chain; the semantics for dropped proofs are not defined | minor |
| L3-9 | `AnchorEngine.java:216-222` (re-read: only `value() == null` is refused) | a pluggable provider answering `ok(new byte[0])` | the note holds empty base64; `AnchorNotes.current` reads it as "no history", so the next `obtain` gets an empty `previous` and a second chain start begins | minor |
| L3-10 | `SignedAnchorProvider.java:186-203` (re-read: `split` only parses; no signature check on the previous proof) | a well-formed statement dated 2999-01-01 written by anyone with write access to `refs/notes/anchors/signed` | every later `obtain` returns `REFUSED CLOCK_BEHIND_PREVIOUS` and anchoring is blocked until the history is repaired; CI reports it only afterwards as `PROOF_REJECTED` | minor |
| L3-11 | `AllowedSigners.java:167` (re-read: `Path.of(file)` outside a try; the same pattern at `:147`, the PLAN-10 L3-2 shape) | a configured path containing U+0000 | `StoreProviders.select` throws `InvalidPathException` instead of returning `MALFORMED`; `VerifyAnchors` reports "signing.allowed-signers: not readable" for an anchor file (misleading detail, leaf-reported at `:79`) | minor |
| L3-12 | `StoreClone.java:334-337` (re-read: `close()` only closes the repository, no closed flag) | a due check queued after a save, release or fetch and not yet started when the caller closes the writer without cancelling the schedule | it runs against the closed clone (the PLAN-09 lesson: check-then-act on a closed flag under the lock `close()` takes); what JGit does on a closed repository is not shown | minor |
| L3-13 | `SignedAnchorProvider.java:277-280`, `:300` (leaf-reported) | a holder of an expired or revoked key appends proofs dated at the last legitimate proof's time | accepted: validity is checked against the signer's claimed time and chain times only have to be non-decreasing; inherent to the format, the spec asks for no more | info |
| L3-14 | `Checkpoints.java:355`, `AnchorNotes.java:433`, `AnchorEngine.java:170,208` (leaf-reported) | one failed push | two WARN records for the same failure (`ANCHOR_PUSH_INCOMPLETE`, then `ANCHORING_PENDING`); alias and outcome only | info |

Points the leaf checked and the orchestrator did not re-read (leaf-reported, recorded as such): the concurrency design (lock
order anchoring, shared maintenance, transport has no cycle; the checkpoint tag pushed against a zero-id lease and never moved;
the proof bound to the notes tip it extends, `AnchorNotes.publish` 401-440; kept state under `refs/anchoring/` outside every
prune scope); key separation by content (`StoreProviders.java:281`, `SshSignatures.java:366`); the SSHSIG namespace and format
checks (`SshSignatures.java:273-281`, signature type, hash, length 64); the secrets rules on the new log records (alias and
outcome only, no throwable, `GIT_STORE-7` carries alias, object, time, sequence and the SHA-256 only); head-of-line blocking
as the price of the chain (`AnchorEngine.java:177-185`).

**Layer 3 not shown by reading:** that the interleavings hold in a JVM run; that `git-core`'s push honours a zero-id expected
remote head as "must not exist" and that `GitNotes.write` writes onto the moved tip as `AnchorNotes.follows` assumes; behaviour
after a crash between `keep()` and the push; what JGit does with a `RefUpdate` after the repository is closed; whether
`verify -Ppre-commit` and the Sonar gate were green at the head.

**Layer 3 verdict:** findings — 1 major (L3-1), 11 minor (L3-2 to L3-12), 2 info; not shown by reading — 5

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, `@NullMarked`, Javadoc on every public type and method | conforms | the single package is already exported; new public types carry Javadoc (leaf-reported) |
| AGENTS.md | no dependency without approval; `.github/project.yml` untouched | conforms | no pom change, imports are JGit, jspecify, `cui-java-tools` only (leaf-reported) |
| AGENTS.md | expected outcomes are results, not exceptions (GDR-GEN-3) | deviates | L3-6, L3-7, L3-11 |
| AGENTS.md | `CuiLogger` with a `LogRecord` holder, catalogue (GDR-GEN-4) | conforms | INFO 7-10 and WARN 111-112 documented in `LogMessages.adoc` (leaf-reported) |
| AGENTS.md | documentation: state lists and the "planned" wording | conforms | `AGENTS.md`, `module-structure.adoc`, `doc/README.adoc` and the spec intro updated (leaf-reported); the spec still says "Planned is GDR-STORE-19", which is consistent with main |
| spec constraint | secrets (GDR-NFR-3), no throwable on a sanitised path | conforms | no log call attaches a throwable in the new files (leaf-reported; `WritePathSecretsTest` extended, its run not shown) |
| spec constraint | key separation at start | conforms | `StoreProviders.java:281`, test `StoreProvidersTest:353` (leaf-reported) |
| spec constraint | a deliberate `catch (RuntimeException ...)` carries its justification in the accepted form | conforms | a comment and `cui-rewrite:disable InvalidExceptionUsageRecipe` above each catch (leaf-reported) |
| spec constraint | Sonar: fix, or dismiss on the operator's selection through `sonar_rest transition` | deviates | L2-1: three in-code suppressions |
| spec constraint | tests: one deadline, cancellation, `shutdownNow` with `awaitTermination`; one throwing call per `assertThrows` | deviates | L2-2 (the executor tests comply, `AnchorInterleavingTest` does not) |
| spec constraint | interleavings tested with a pause point; mirror prune cannot delete local-only state | conforms | `AnchorInterleavingTest:291`, `refs/anchoring/` outside the prune scope (leaf-reported); L2-3 is the residue |
| CLAUDE.md | JGit API claims checked against JGit 7.8.0 and the version named | conforms | named at `AnchorVerification.java:47,65-67` and `Checkpoints.java:200` (leaf-reported); the zero-id lease semantics lives in `git-core` and is not tied to a source |

## Metrics and Anomalies

- Tokens: the landing says `total_tokens=23347271`; the retrospective aspect quoted in candidate message 009 says 7,406,332 tokens (6-finalize 4,631,964, 63 %), about 3x the 2.5M anchor. The two differ by a factor of about 3.2 and were not reconciled (a difference of definition is likely: the landing counts every dispatched leaf, the retrospective the phase totals). Same factor as PLAN-10 (2.5).
- Duration: the landing says `total_wall_seconds=34548` (576 min); message 009 says 183 minutes of worked time against a 180-minute error bar. Not reconciled.
- Anomalies: four finalize loop-backs (PR re-open, two review and Sonar fix rounds, one inline Sonar fix); `finalize-step-simplify` fired 7 times, `pre-push-quality-gate`, `ci-verify` and `sonar-roundtrip` 5 times each, `automatic-review` 6 times; five of ten terminal finalize steps left no token record; all 28 dispatch-boundary rows are keyless (lesson `2026-11-18-07-006` now carries this recurrence).

## Routing and Merge Behavior

- Review: CodeRabbit filed 16 inline findings, remediated through fix tasks TASK-8 to TASK-13 and TASK-14 to TASK-16 in two loop-backs (the classes are in lesson `2026-11-24-07-002`). PR #65 was closed unmerged and replaced by #66 on the same head branch when `automatic-review` re-opened it so the required reviewer would review after its quota window (lesson `2026-11-18-13-005`, recurrence added).
- CI/merge: merged as `b30ec14` through the merge queue (PR state `merged`, merge commit read through `gh pr view`); `cleanup_owed=false`.
- Sonar: the landing says `sonar_open_new_issues=0`; the merged-head read was repeated and is undecidable (`count_status: undecidable`, `head_revision_unavailable`, analysis revision `4db53c7`), so the `0` is not re-verified. Three suppressions were operator-selected by the lane's account (keys `AaIJ8bNkds2dYlt-5XQ2`, `AaIJ8bNKds2dYlt-5XQz`, `AaIJ8bMeds2dYlt-5XQv`).
- Review-gate: the `review-gate` re-read is undecidable (`unreadable review-body section`, CodeRabbit "Minor comments: 1"); the three review numbers of the landing are `unknown`.
- Completeness: `inbox landing-check` reports `complete: false`, missing `effort`, `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` (no finalize step records them).
- Collision: PLAN-11 ran beside PLAN-13 and PLAN-20; the plan's diff touches `AGENTS.md`, `doc/README.adoc` and `module-structure.adoc` but no `pom.xml`, `general.adoc` or `git-core`, so the one overlap actually realised with the other two plans is the state lists. The landing does not say whether the rebase conflicted.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped `66`
- [x] row `landing` stamped `landings/PLAN-11.md`
- [x] row `plan_marshall_plan_id` stamped `plan-11-store-anchoring-signed` (set at the launch)
- [x] claim verdicts persisted: claim 0 and claim 1 `corroborated` at `b30ec14`
- [ ] epic.md: Open Defect for the PLAN-11 layer-3 findings (NOT YET ROUTED), Open Defect entry for the incomplete landing, release Watch updated
- [ ] lessons: 11 candidate messages dispositioned (see the log)
- [ ] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- L3-1 to L3-12, L2-1 to L2-3: reported to the operator, not routed. Candidate: PLAN-22 (the `git-store` hardening plan), with PLAN-10's ten minors; the major (L3-1) is the one defect of the epic that makes a CI check report clean over a broken invariant.
- L1-2: PLAN-17 implements the `AnchorProvider` SPI and reads the merged signature from `main`; its spec does not name the signature, so nothing is folded.
- L1-4: the Done-when "every GDR-STORE requirement is covered" is an authoring slip (GDR-STORE-19 stays planned); no correction needed, the spec is shipped.
