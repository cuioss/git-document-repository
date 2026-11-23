# Landing Analysis: PLAN-10 — git-store: commit signature, identity trailers, signature verification

epic: libraries-initial-implementation
workstream: WS-02
pr: #58 (https://github.com/cuioss/git-document-repository/pull/58), merged as `ad9e773` (first parent `95c1a97`), through the merge queue

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the lane's claims against git, the PR
> and the merged code. The landing message is `inbox/plan-10-store-signing-trailers-verify-015.md`.

## Deliverable Fidelity vs Spec

The spec numbers four deliverables; the landing facts say `deliverables_total=5, deliverables_done=5` (the lane's own task
split, not re-derived: the spec has four). Evidence is the merge diff `git diff 95c1a97 ad9e773` (48 files, +7116 / -271) and
the leaf's reading of the merged code.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Signature encoding decision | shipped-as-specified | Own SSHSIG encoder, no dependency; decision recorded in `doc/specification/git-store.adoc` (Decision 2026-11-23), `doc/specification/general.adoc` (GDR-GEN-1 row) and `doc/concepts/identity-and-signing.adoc`; `pom.xml` is not in the diff, which is the consequence of "no dependency" |
| 2. `Signer` over the active `SigningKeySource`, source `file` | shipped-modified | `SshSignatures`, `SourceSigner`, `FileSigningKeySource` (Ed25519 only, others `REFUSED`), `CommitSignatures`, wired into every `ExplicitSave` commit path and both release kinds. Reduced scope: no checkpoint or notes commit exists on `main` yet (GDR-STORE-16/17), so the spec's "save, release, checkpoint and notes" is met for save and release only (L1-2) |
| 3. Identity trailers on save and release | shipped-as-specified, with one deviation | `IdentityClaims`, `IdentityTrailers`; draft commits carry none; no `sub` or `sid`. `Actor` is the caller's `Author.pseudonym`, not a `PersonDirectory` lookup (L1-3, the existing `Author` contract) |
| 4. Verification against `signing.allowed-signers` | shipped-as-specified | `AllowedSigners`, `SignatureVerification`, CI main class `VerifySignatures` (exit 0/1/2); stock `git verify-commit` cross-check in `GitVerifyCommitCompatibilityTest`, gated on Git 2.35 and `ssh-keygen` |
| (added) shared `StoreResult.name()`, `StoreClone`/`CloneOpening` wiring, `identity-and-signing.adoc`, `module-structure.adoc`, `LogMessages.adoc`, `ExternalTools`, `TestKeys` | added, explained | needed by the signer wiring or documentation; the `StoreResult.name()` consolidation replaces three private copies and is an unrequested refactor |

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`), by reading only: no build, no test
run, no write. The orchestrator re-read the layer-3 points listed below at their lines in `ad9e773` before recording them; the
points it did not re-read are marked "leaf-reported".

- **Dispatch:** one read-only leaf per landing — reason: reading 7000 lines of merged security code is the depth the dispatch rule exists for, and its context stays apart from the drain of the 15 messages.
- **Verdict:** layer 1 findings (2 minor, 2 info, 1 not shown); layer 2 findings (1 minor, 2 info, 1 not shown); layer 3 findings (10 minor, none blocker or major, one of them an exception where the rules want a result) — see the sections below.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | HYPOTHESIS "JGit 7.8.0 ships `org.eclipse.jgit.ssh.apache` `SshSigner` that signs with a caller-supplied key" is not shown: the artifact is not in `~/.m2` and the core sources hold no SSH signer. `Signer.sign` takes a key name, not key bytes. The own-encoder decision holds without it, because `SigningKeySource.sign` returns raw signature bytes (KMS-shaped) and the SSHSIG blob has to be built either way | not_shown_by_reading (premise); checked (decision) | none; confirms | `git-store.adoc:660`, `SourceSigner.java:88` (leaf-reported) |
| L1-2 | Spec deliverable 2 and the specification text say the signer covers "save, release, checkpoint and notes"; `git-store` writes no checkpoint or notes commit on `main` (GDR-STORE-16/17 are PLAN-11), so the premise was satisfiable only for save and release and the cut was not flagged | checked | refutes | `git-store.adoc:665` (leaf-reported) |
| L1-3 | Spec says `Actor` comes "from the active `PersonDirectory`"; the code takes `Author.pseudonym` from the caller, validated only against `[A-Za-z0-9-]+`: any matching string is accepted. This is the existing `Author` contract, not a regression | checked | refutes | `git-store.adoc:758` (leaf-reported) |
| L1-4 | Done-when "Sonar open-finding read at the head is 0 or operator-selected" is a remote fact | not_shown_by_reading | none | — |

**Layer 1 verdict:** findings — 2 minor (L1-1, L1-2), 1 info (L1-3), not shown by reading — 1 (L1-4)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1, 2 | `SshSignatures`, `SourceSigner`, `FileSigningKeySource`, `CommitSignatures`, `ExplicitSave`, `DocumentRelease` | `CommitSignatureTest`, `SourceSignerTest`, `FileSigningKeySourceTest`, `SshSignaturesTest` (names carry GDR-STORE-12) | none beyond the added files above | checked (leaf-reported) |
| 3 | `IdentityClaims`, `IdentityTrailers` | `IdentityTrailersTest` (12 methods, refusal per claim, trailers on save and release only), `ReleaseTest` | none | checked (leaf-reported) |
| 4 | `AllowedSigners`, `SignatureVerification`, `VerifySignatures` | `AllowedSignersTest`, `SignatureVerificationTest`, `VerifySignaturesTest`, `GitVerifyCommitCompatibilityTest` | none | checked (leaf-reported) |

| Finding | Claim | Verdict | Polarity |
|---------|-------|---------|----------|
| L2-1 | No test for a merge commit, a commit with a `mergetag` header or a second `gpgsig` header, or an oversized or non-UTF-8 `allowed_signers` file; `unsignedPayload`'s continuation and duplicate-header handling is covered only by the stock-git cross-check, which is skipped when `git` or `ssh-keygen` is absent. L3-1 and L3-3 are untested | checked (leaf-reported) | refutes |
| L2-2 | Whether the `git` and `ssh-keygen` cross-checks ran or skipped in CI is not shown: the `assumeTrue` gates mean a green build can hold zero executed cross-checks, and they are the only external proof of the armor and the `gpgsig` folding | not_shown_by_reading | none |
| L2-3 | The lane's reported facts that the diff bears out: `SshSignatures` 561 lines, Ed25519 only with other types `REFUSED`, `pom.xml` untouched, `git-core` untouched. "Six review-fix rounds" is not checkable from git | checked (diff stat re-read by the orchestrator) | confirms |

**Layer 2 verdict:** findings — 1 minor (L2-1), not shown by reading — 1 (L2-2); every deliverable traces to code and to a test that can fail

### Layer 3 — Correct

Re-read by the orchestrator at `ad9e773` unless marked leaf-reported. No blocker or major defect; none is shown to defeat
signing, the trailers or the verification verdict.

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `AllowedSigners.java:115` | `Files.readAllLines` with no size or line bound, unlike the 64 KiB bound of `FileSigningKeySource`; a huge file or a FIFO as `signing.allowed-signers`; invalid UTF-8 | `OutOfMemoryError` escapes; invalid UTF-8 is `UNAVAILABLE` "not readable", not `MALFORMED`. The path is operator configuration, so this is hardening | minor |
| L3-2 | `AllowedSigners.java:136` | `Path.of(file)` outside any try: a `signing.allowed-signers` value that is no valid path (U+0000 passes the string-only `ConfigurationTree`) | `InvalidPathException` instead of a `StoreResult` (GDR-GEN-3); `FileSigningKeySource` and `VerifySignatures` catch it, this entry point does not | minor |
| L3-3 | `SignatureVerification.java:221-241` against `check` at `:188` | `unsignedPayload` strips every header starting with `gpgsig `, `check` verifies only the first (`getRawGpgSignature`): a copy of a validly signed commit with an extra `gpgsig <junk>` header | reported valid under a different commit id; tree, parents and message stay covered, so object-id malleability, not content forgery. Stock Git's behaviour is not shown by reading; no test | minor |
| L3-4 | `SignatureVerification.java:204` | validity uses the committer time, which the signer writes inside the signed payload: an expired key, or one past `valid-before`, signs a back-dated commit | VALID. This is Git's own verify-time semantics and the spec, but `valid-before` is no revocation against the key holder; GDR-STORE-14 states the rewrite protection, not this | minor |
| L3-5 | `SignatureVerification.java:157`, catch at `:181` | the `RevWalk` retains every reachable commit with its body, plus the unverified list: O(history) memory, no cap or option; the catch is `IOException \| RuntimeException`, not `Error` | a very large reference ends the CI main with an `OutOfMemoryError`, not exit 2; the size at which it bites is not shown | minor |
| L3-6 | `AllowedSigners.java:289` (re-read), and line 223 and `ExternalTools` (leaf-reported) | a `Z`-suffixed timestamp is read as UTC; OpenSSH 8.8 and 8.9 refuse the suffix; one non-Ed25519 line (e.g. `ssh-rsa`) makes the whole file `MALFORMED` | the store accepts what stock Git of those versions rejects; one RSA entry disables verification entirely (fail-closed by design, documented); a date-only `valid-before` means 00:00:00 of that day | minor |
| L3-7 | `CommitSignatures.java:127`, detail at `:115`; `SignatureVerification.java:175` (leaf-reported) | `SIGNING_FAILED` (GIT_STORE-110) and `SIGNATURES_VERIFIED` log the reference, which contains the document id; the standing constraint says log the alias and the outcome only | a document id reaches the log. It follows the module's convention (`SAVE_NOT_COMPLETED` logs the reference too), so it is a convention gap, not a new leak; no key, claim, pseudonym or e-mail is logged and no throwable is attached | minor |
| L3-8 | `IdentityClaims.java:58-72` | `acr` with `;` or a control character, an `amr` entry with `,`, `;` or whitespace, a blank or whitespace-bearing issuer — values an IdP can produce, taken from an ID Token | `IllegalArgumentException` (spec-sanctioned, message names the component only) where the rules want a result: a user whose token carries such an `amr` cannot save; no length bound on issuer, `acr` or `amr` | minor |
| L3-9 | `SourceSigner.java:88` (call site re-read; the catch set of `InCoreWriter` leaf-reported) | `source.sign` and `source.publicKey` (`CommitSignatures.java:89`) are called without isolation from a `RuntimeException` of a third-party `SigningKeySource` | the exception escapes `StoreWriter.save` and `release`, possibly with a message naming the key reference; whether the guard locks are released on that path is not shown | minor |
| L3-10 | `VerifySignatures.java:144` | `catch (IOException \| IllegalArgumentException)` around the whole verification loop | any such failure is printed as `NOT_A_REPOSITORY git-dir: not a repository`; a malformed reference name would be mislabelled | minor |

Points the leaf checked and the orchestrator did not re-read (leaf-reported, recorded as such): the SSHSIG layout against
PROTOCOL.sshsig (`SshSignatures.java:91`, bounds-checked reader, hash in {sha512, sha256}); the bounded, fail-closed key parse
(`FileSigningKeySource.java:136`, 64 KiB, cipher and KDF `none`, one key, check ints, public key equals the seed's); trailer
injection closed (`IdentityTrailers.java:76`); the release-recovery guard (`DocumentRelease`: first paragraph plus the
release-shaped parent structure, no trailer text read, tag recovery before signing, crafted-input tests `ReleaseTest.saveImitatingReleaseSubjectAndTrailersIsRefused`
and `saveOntoMainWithReleaseSubjectOfAnotherEntryIsRefused`).

**Layer 3 not shown by reading:** memory behaviour of the `RevWalk` on a large reference (L3-5); what stock Git does with two
`gpgsig` headers (L3-3); whether a provider exception releases the guard locks (L3-9); native-image reachability of the
`ServiceLoader` provide for `file` (`git-store` ships no native-image metadata, GDR-NFR-4 is not verified in this repository);
whether `LogAsserts` cover every new `LogRecord` and what `WritePathSecretsTest` returns in a run.

**Layer 3 verdict:** findings — 10 minor; not shown by reading — 5

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, `@NullMarked`, Javadoc on every public type and method | conforms | `module-info` adds `provides ... FileSigningKeySource` and the services file (leaf-reported); license headers on all new files not shown |
| AGENTS.md | no new dependency; third-party out of lower modules | conforms | no `pom.xml` change in the diff (orchestrator re-read), `git-core` untouched |
| AGENTS.md | expected outcomes are results, not exceptions (GDR-GEN-3) | deviates | L3-2, L3-8, L3-9 |
| AGENTS.md | `CuiLogger` with a `LogRecord` holder, catalogue | conforms | GIT_STORE-6 and 110 in `LogMessages.adoc`, asserted in `LogMessagesCatalogueTest` (leaf-reported); whether every record has a `LogAsserts` check is not shown |
| spec constraint | GDR-NFR-3: no token, key material, `sub`, `sid`, e-mail or name in a detail, message, log or commit | conforms | details are constants; `WritePathSecretsTest` exists, its run result is not shown (leaf-reported) |
| spec constraint | log only the alias and the outcome, the reference contains the document id | deviates | L3-7 |
| spec constraint | key material never in clear text, test keys generated per run, `git-core` read-only | conforms | `KeyMaterial.clear()`, `TestKeys` per call, no `git-core` file in the diff (leaf-reported; the last re-read) |
| spec constraint | a deliberate `catch (RuntimeException ...)` carries its justification in the accepted form | conforms | `SignatureVerification.java:178-181`: the two-line comment, then `// cui-rewrite:disable InvalidExceptionUsageRecipe`, then the catch (re-read); that the recipe accepts a multi-catch is not shown |
| spec constraint | a recovery guard identifies its target by data only the operation can produce, tested with a crafted input | conforms | leaf-reported (see above) |
| CLAUDE.md | JGit API claims checked against JGit 7.8.0 and the version named | conforms | Javadoc names 7.8.0; `Signer`, `GpgConfig`, `getRawGpgSignature` match the 7.8.0 sources jar (leaf-reported); `getShallowCommits()` and `getWhenAsInstant()` not re-read |

## Metrics and Anomalies

- Tokens: the landing says `total_tokens=19886672`; the retrospective aspect quoted in candidate message 014 says 8.04M tokens, 56 % of it (4,518,457) in `6-finalize`. The two differ by a factor of about 2.5 and were not reconciled (a difference of definition or a double count; not shown which).
- Duration: the landing says `total_wall_seconds=39720` (662 min); message 014 says 331 min against a 180 min anchor, about 5 of 7.5 hours in finalize loops. Same factor of 2.
- Anomalies: six review-fix rounds against an allowance of three (the operator granted three extra); Sonar new-code issues 18, 6, 2, 1 across pushes; a baseline-drift reconcile through `2-refine` when PR #59 landed in the middle (conflicts in `doc/README.adoc` and `doc/specification/general.adoc`, none lost); `effort` was not stamped by `phase-1-init`. The landing's `deliverables_total=5` does not match the spec's four.

## Routing and Merge Behavior

- Review: CodeRabbit ran four rounds (commits `8fca54a`, `c156f44`, `9ae4572`, then a clean pass), 17 pr-comment findings by the lane's account (13 fixed, 3 taken into account or accepted, fix tasks TASK-10 to TASK-18). The landing carries `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` as `unknown`. The re-read `runbook_lane review-gate --pr-number 58` is **undecidable**: `unreadable review-body section: 'Minor comments: 20'` (the CodeRabbit review-body section header, the same class as PLAN-02, 03, 05, 08, 12). Nothing is re-verified; the lane's 17 is a lead.
- CI/merge: merged as `ad9e773` through the merge queue (PR state `merged`, `merge_commit_sha` read through `ci pr view`); `cleanup_owed=false`.
- Sonar: the landing says `sonar_open_new_issues=0`; the merged-head read was not repeated here (earlier merged-PR reads of this epic returned `head_revision_unavailable`), so the `0` is not re-verified.
- Collision: the PLAN-10 run met the PLAN-23 merge on `doc/README.adoc` and `doc/specification/general.adoc`, which both plans declared in their Expected Surface, so the gate predicted the pair and the collision is a pairing already known (see the Watch on shared documents).

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped `58`
- [x] row `landing` stamped `landings/PLAN-10.md`
- [x] row `plan_marshall_plan_id` stamped `plan-10-store-signing-trailers-verify`
- [x] epic.md: Open Defect for the PLAN-10 layer-3 findings (NOT YET ROUTED), Open Defect entry for the incomplete landing, the Watch on shared documents folded, the release Watch updated
- [x] 11 of the 14 candidate lessons filed (2026-11-23-14-001 to 011); message 007 folded into PLAN-11; 012 folded into the shared-documents Watch; 013 folded into lesson 2026-11-23-14-010
- [x] PLAN-11 spec: standing constraint for the PLAN-10 review lessons and the checkpoint and notes signing seam, no surface change
- [x] resume anchor updated; `queue-view.md` regenerated

## Follow-Ups

- L3-1 to L3-10 and L2-1: reported to the operator, not routed. Candidate: PLAN-22 (the `git-store` hardening plan, now strictly sequential after PLAN-11 because it edits the same files), as deliverable 4, on the operator's word.
- L1-2: PLAN-11 must route its checkpoint and note commits through the `CommitSignatures` seam; folded into the PLAN-11 spec.
- L1-3: when `git-directory` provides `PersonDirectory`, the `Actor` pseudonym should come from the directory, not from the caller; a note for PLAN-15, not folded (no staged text to correct).
