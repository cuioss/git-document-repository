# PLAN-11: git-store — anchoring: subjects, checkpoints, proofs as notes, own timestamp signed

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Complete `git-store` with the anchoring: which subjects are anchored and when (release commits, checkpoints with
their `refs` file and never-moved `refs/tags/checkpoints/<n>` tags — GDR-STORE-16), proofs stored as base64 notes
under `refs/notes/anchors/<provider>`, pushed with lease, pending subjects derived from Git state and retried, and
`verifyAnchors()` from CI (GDR-STORE-15, GDR-STORE-17), and the own timestamp provider `signed` with its signed
statement chain (GDR-STORE-18).

## Deliverables

1. Anchoring engine (GDR-STORE-15/16): at most one active provider by `anchor.provider`; release-tag commits anchored
   unless `anchor.releases` is false; checkpoint commit (tree `refs`, parent the previous checkpoint, technical user,
   `Checkpoint <n>`) when `anchor.checkpoint-interval` passed and a listed reference changed (checkpoint tags and
   anchor notes never make one due); due check after every push/fetch and every `anchor.retry-interval`; a failing
   provider never blocks a save, release or fetch.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Proofs as notes (GDR-STORE-17): base64 note on the anchored commit, pushed with lease right after writing,
   separately from save commits; pending = subjects without a remote note as of the last fetch; a local-only note is
   pushed, not re-requested; `previous` = newest anchored proof.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Provider `signed` (GDR-STORE-18): the statement format `anchor signed 1` / object / time / sequence / previous,
   armored SSH signature with namespace `git-document-anchor`, anchor key from `anchor.signed.key-source` /
   `anchor.signed.key` (start fails if equal to the commit-signing key), clock checks against the previous proof and
   `anchor.signed.max-clock-skew`, INFO log record per proof (alias, object id, time, sequence, SHA-256 of the proof).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Verification: `verifyAnchors()` walks the checkpoint chain and release tags, checks every proof, lists unreachable
   or retargeted references a checkpoint lists and missing checkpoint numbers; `signed` chain checks (no gaps,
   predecessor hash, non-decreasing time) against `anchor.signed.allowed-signers`. Tests incl. an `ssh-keygen -Y
   verify` cross-check when OpenSSH is present (skipped otherwise).
   - Skills: pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: module-tester

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
- The provider only obtains and verifies proofs; every decision about subjects, notes and pushes stays in the engine,
  so `rfc3161` (PLAN-17) plugs in without engine changes.
- The save's fetch prunes (`FetchInput.prune` with `JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS`, PLAN-08): it deletes
  every local reference below `refs/heads`, `refs/tags` and `refs/notes` whose source the remote does not advertise (JGit
  7.8.0 `FetchProcess.deleteStaleTrackingRefs`; `git-core.adoc` § fetch). A local-only proof note, checkpoint tag or
  checkpoint branch written before its push is therefore lost to a pruning fetch of a concurrent save: write and push it
  under the one transport lock of the clone (`RepositoryGuard`), or keep it out of the pruned set, and test the
  interleaving with a pause between the write and the push. "A local-only note is pushed, not re-requested"
  (deliverable 2) holds only if the note survives until that push.
- Review lessons of PLAN-08 (`landings/PLAN-08.md`): a green local build was followed by 18 valid bot findings and 33
  Sonar findings, mostly races. For every reference operation that reads and then writes or deletes, name the locks and
  the readers between the check and the compare-and-swap and test each with a pause point; capture identifiers once and
  re-apply them on a retry; do not read a result code with several causes (`LOCK_FAILURE`) as one cause; derive remote
  state from the fetch result, never from mirror references; give a destructive option (prune) an explicit scope; after an
  irreversible remote effect report a later failure without contradicting it; detect a skipped operation from evidence
  that it ran, not from a pre-check; a lock covers the reads its decision depends on; never attach a throwable to a log
  record on a path promised sanitised (DEBUG included, GDR-NFR-3); a test asserts content, takes fixture values that differ
  by construction, and ends a concurrent scenario with one deadline, cancellation and `shutdownNow` with
  `awaitTermination` (never `ExecutorService.close()`); one throwing call per `assertThrows` lambda, no unused `throws`,
  JGit returns (`getRef`, `resolve`, `getObjectId`) treated as `@Nullable` (Sonar S5778, S1130, S4449). Plan for a second
  review pass.
- `git-core` stays read-only (PLAN-08 edited it against its outline; report a `git-core` gap instead of absorbing it).
  PLAN-09 did the same for `TreeEdit.Mode`, owner-approved after the fact; `git-core` still pushes one reference per call, so
  a checkpoint and its tag, like a release and its tag, cannot go up atomically: report the incomplete state and repair it on
  the next due check, as the release does.
- Review lessons of PLAN-09 (`landings/PLAN-09.md`; again many valid bot findings after a green build, mostly races): a recovery
  or repair guard (here: the pending-subject derivation and the repair of a missing note or tag) identifies its target by data
  only the guarded operation can produce, never by free text the caller controls, and is tested with a crafted input that
  imitates the scenario; a boundary that event delivery depends on is captured while the lock that made the change is held;
  a callback never blocks on a lock another callback may hold, and a check-then-act on a closed flag happens under the lock
  `close()` takes; a reference name or path contains the document id, so log only the alias and the outcome, DEBUG and
  fallback records included; recovery of a failed derived state is not gated on the next change event; a test of a
  tree-producing operation (the checkpoint tree) compares file modes as well as bytes; a Sonar S6539 fix lowers the count of
  every class it touches, and a deliberate `catch (RuntimeException ...)` carries its justification in the form the
  OpenRewrite recipe accepts, so no `java:S1135` placeholder reaches new code.
- Review lessons of PLAN-10 (`landings/PLAN-10.md`; six review loop-backs against an allowance of three, 56 % of the plan's
  tokens in finalize, Sonar new-code issues 18, 6, 2, 1 across pushes): the `signed` provider adds a security format (the
  statement `anchor signed 1`) and a verifier, which is the class that needed the rounds, so budget for three or more review
  rounds and read the reference implementation's refusals before the first commit (for allowed signers: OpenSSH `sshsig.c`;
  list each refusal and test it: repeated options, empty or inverted intervals, strict calendar dates, pattern syntax,
  duplicates, and refuse a feature not implemented at parse time instead of storing it as a literal). A verifier that reports
  "every item is valid" proves it saw every item against the state the walk used (shallow boundary, missing objects), never a
  snapshot taken earlier. Check-then-use on bytes from a pluggable provider uses the verified defensive copy downstream, never
  the caller-owned buffer. A capability probe that guards an interoperability test is independent of the code under test
  (fixtures from the external tool itself, the same executable the test uses, subprocess output to a file, a deadline,
  destroy in `finally`, `GIT_CONFIG_GLOBAL` and `GIT_CONFIG_NOSYSTEM` for isolation). For each requirement, check every
  "stays", "never", "every" and "is refused" sentence of the specification against the file that would enforce it. Before the
  first push run the Sonar-class rules the project gates on over the new code in one pass (records, method placement, record
  patterns, no `java:S1135` placeholder) instead of one rule family per push. PLAN-10 shipped signing for save and release commits only,
  because no checkpoint or notes commit existed on `main` (`landings/PLAN-10.md` L1-2): the checkpoint and note commits of this plan
  are signed through the same seam, `CommitSignatures` (`CommitSigning(signer, null, null)` on the in-core write), and carry no identity
  trailers (GDR-STORE-13 limits them to save and release); a test shows a checkpoint and a note commit verify under
  `SignatureVerification`. No file surface is added (`git-store/` is already declared).

## Done when

- `verify` passes at the plan's head with GDR-STORE-15/16/17/18 each covered by a test naming the requirement id;
  every GDR-STORE requirement is then covered.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the checkpoint tree format, the statement format and the namespace `git-document-anchor` are specified
  in `doc/specification/git-store.adoc` § GDR-STORE-16 and § GDR-STORE-18.
  - verdict: corroborated | checked_at: b30ec140b6e178afcb251e3df9a8b4e18bf68f9d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at b30ec14: the checkpoint tree (sorted refs file, LF lines), the five-line statement and the namespace git-document-anchor in git-store.adoc (GDR-STORE-16 825-846, GDR-STORE-18 867-893) match the code.
- HYPOTHESIS: the SSHSIG armoring chosen in PLAN-10 can sign arbitrary bytes with a custom namespace (not only commit
  payloads) — confirm/refute at the implementation PLAN-10 lands (its `Signer` / SSHSIG encoder type in
  `git-store/`) (verify-at-outline).
  - verdict: corroborated | checked_at: b30ec140b6e178afcb251e3df9a8b4e18bf68f9d | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at b30ec14: SshSignatures.signedData(namespace, message) 107, encode(blob, namespace, signature) 145 and verify(decoded, namespace, message) 272 take the namespace as a parameter and sign arbitrary bytes; SignedAnchorProvider uses it with git-document-anchor.

## Expected Surface

- HYPOTHESIS: `git-store/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-02 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-store.adoc`
- HYPOTHESIS: `AGENTS.md` ("Current state" paragraph: new public classes of `git-store`)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-09 (release tags), PLAN-10 (SSH signature format, key source).
- Overlaps with: every WS-02 plan — strictly sequential; PLAN-17 implements the same `AnchorProvider` interface.
- Adjacent to: `git-timestamp` — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-11-store-anchoring-signed.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
