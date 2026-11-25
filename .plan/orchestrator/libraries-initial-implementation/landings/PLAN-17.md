# Landing Analysis: PLAN-17 — git-timestamp: RFC 3161 anchor provider and token verification

epic: libraries-initial-implementation
workstream: WS-05
pr: #74 (https://github.com/cuioss/git-document-repository/pull/74), squash-merged through the merge queue as `729ca28f8b670943d5b425ffa96934f7fda9f976` on 2026-11-24

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-25 from the landing message
> `plan-17-timestamp-rfc3161-012` (the plan's inbox message), corroborated against git, the PR through the CI abstraction and the merged
> code. The message is a lead, not a fact; what follows states what was checked.

## Deliverable Fidelity vs Spec

The spec staged four deliverables; the lane reported five (`deliverables_total=5`, `deliverables_done=5`), the fifth being the document and
state updates. Everything the spec constrained held: no dependency, no change to `git-store`, no change to `.github/project.yml` (the
squash has 32 files and none of them is a `git-store` or `.github` file).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. RFC 3161 library decision put to the owner, recorded in `git-timestamp.adoc` and `general.adoc` § GDR-GEN-1 | shipped-as-specified, one point not shown | the decision is recorded ("Decision (2026-11-24) … owner"): **no library**, an own bounded DER/CMS reader and writer, so `git-timestamp/pom.xml` lists only `git-store`, JSpecify, `cui-java-tools` and test-scope libraries already in the reactor. That the owner was asked BEFORE any code is not visible in a squash diff |
| 2. Module creation: pom, `module-info` `provides`, `LogRecord` holder, root `<modules>`, layering guard, state documents | shipped-as-specified | `module-info.java` 34-42 (`provides AnchorProvider with Rfc3161AnchorProvider`), `git-timestamp/pom.xml` 70-87 (`module-layering` include `git-store`), root `pom.xml` `<module>`, `GitTimestampLogMessages` (INFO 1, WARN 100-102) with `git-timestamp/doc/LogMessages.adoc`, `AGENTS.md`, `module-structure.adoc` |
| 3. Provider (GDR-TSA-1): request, nonce, `certReq`, outcomes, configuration, secrets | shipped-as-specified | `Rfc3161AnchorProvider` 134-145 (request), 162-203 (`obtain`), 205-213 (refusals); tests `Rfc3161ObtainTest` 157-179, 213-224, 693-728, 730-759 and `TimestampSecretsTest`. The lane also made `obtain` verify the token before returning it (196-200), because the anchoring engine publishes an obtained proof without verifying it (`AnchorEngine` 198-206) |
| 4. Verification (GDR-TSA-2) and tests without network access, an end-to-end anchoring run | shipped-as-specified for the end-to-end run, **partly for the rules** | `Rfc3161AnchoringTest` 222-269 (a clean report with `anchor.provider = rfc3161`; an unreachable authority leaves the release commit pending); the verification rules are implemented (`TimeStampTokens` 210-573) but several have no test that can fail (L2-1) |

Added-unplanned, not in the spec: `doc/resources/diagrams/module-dependency-graph.svg` (explained by the module graph of deliverable 2, not
declared) and a rewrite of the `DomainTermAbsenceTest` trade-off text in `general.adoc` that corrects existing `git-forms` documentation.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`) at `729ca28`, by reading
only, with no build, no test run and no write. The orchestrator re-read the points that carry a finding or a claim of the report at their
`file:line` before writing them here: `TimeStampTokens` 318-332 and 454-466, `TimeStampRequests` 460-465, `TimestampTransport` 178-188 and
379-382, `TimestampConfiguration` 360-363, `Rfc3161AnchorProvider` 234-252 and 264-267, `Der` 355-375, `AnchorNotes` 262-266 and 320-325,
`TestTimestampAuthority` 425-460 and `Rfc3161VerifyTest` 165-182 (with a grep of that file for the rules L2-1 names). Points that rest on
the leaf alone and were NOT re-read: the signed-attribute checks beyond 323-330, the PKIX search bounds, the transport's reply bound and
credentialed-request guard, the RSA and `sid` branches, and the secrets tests.

- **Dispatch:** one read-only leaf per landing — reason: a new 32-file module with its own DER/CMS code is the depth the dispatch rule exists
  for, and the read must not share context with the PLAN-13 landing analysed the same day.
- **Verdict:** layer 1 findings (2 minor); layer 2 findings (**1 major**, 3 minor); layer 3 findings (no blocker, no major, 6 minor, 1 not
  shown by reading).

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | the specification never says which trust the TLS connection to the authority uses: the code uses the JVM default `SSLContext`, not `timestamp.trust`, so an authority behind a private CA needs the JVM trust store changed and nothing says so | checked | refutes | `TimestampTransport.java` 178-186; `git-timestamp.adoc` (no sentence on it, grep of tls, https, truststore, SSLContext) |
| L1-2 | the owner's decision on the library is asserted, not shown | not_shown_by_reading | none | `git-timestamp.adoc`, `general.adoc` |
| L1-3 | the premises hold together: the spec's four deliverables, GDR-TSA-1/2, the layering entry and the `AnchorProvider` interface agree; the lane found that the engine publishes an obtained proof unverified (`AnchorEngine` 198-206) and made `obtain` verify it | checked | confirms | `AnchorProvider.java` 37-80; `Rfc3161AnchorProvider.java` 196-200 |

**Layer 1 verdict:** findings — 1 (L1-1), not shown by reading — 1 (L1-2).

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 1 decision, 2 module | `pom.xml`, `module-info.java` 41 | `Rfc3161ObtainTest` 747-759 (the `provides` directive) | `module-dependency-graph.svg` (explained, undeclared) | checked |
| 3 provider | `Rfc3161AnchorProvider` 134-213 | `Rfc3161ObtainTest` 157-179, 213-224, 693-728 | none | checked |
| 4 end-to-end | `AnchorEngine` through `Rfc3161AnchorProvider` | `Rfc3161AnchoringTest` 222-269 | none | checked |
| 4 verification rules | `TimeStampTokens` 210-573 | **L2-1**: the test authority builds only well-formed tokens; deleting the `messageDigest` check (327-330) or the `contentType` check (323-326) leaves every test green, and so would the signer count, the digest and signature algorithm refusals and the extended key usage branches; the only mutation is a flipped last byte (`Rfc3161VerifyTest` 168-180), which exercises the signature value | | checked |

| Finding | Claim | Severity | Evidence |
|---------|-------|----------|----------|
| L2-1 | the core verification rules of GDR-TSA-2 have no test that can fail | **major** | `TestTimestampAuthority.java` 430-456; `TimeStampTokens.java` 323-330; `Rfc3161VerifyTest.java` 165-182 (re-read, grep empty) |
| L2-2 | the RSA verification paths (`rsaEncryption` with SHA-256, -384, -512) and the `sid` by subject key identifier are never run: the test PKI is EC P-256 only | minor | `TimeStampTokens.java` 164-177, 412-423, 672-677; `TestCertificates.java` 54 |
| L2-3 | every test injects an `HttpClient` with a test `SSLContext`; the production path, the JDK default context, the hostname check and a TLS failure mapped to `UNAVAILABLE`, is never run | minor | `Rfc3161AnchorProvider.java` 125; `Rfc3161ObtainTest.java` 99 |
| L2-4 | `module-dependency-graph.svg` is touched and not declared; the `DomainTermAbsenceTest` text in `general.adoc` is rewritten and no deliverable names it | minor | `git diff 729ca28^ 729ca28 --stat` |

**Layer 2 verdict:** findings — 4 (1 major, 3 minor). No test was run, so "can fail" is read from the assertions, not shown by a run.

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `TimeStampTokens.java` 457-464 | a signer certificate that is itself in `timestamp.trust` | only `checkValidity(genTime)` runs: the JDK's certificate-path algorithm and key-size constraints (`jdk.certpath.disabledAlgorithms`) are not applied, so a short-key or weak-signature certificate in the trust list is accepted while the path branch refuses it; the operator controls the list, so the risk is low | minor |
| L3-2 | `TimeStampRequests.java` 463 | a `TSTInfo` that carries a `tsa` field | parsed and discarded: RFC 3161, 2.4.2 says it must correspond to a subject name of the verifying certificate, and this is not checked; no trust impact (the trust comes from the certificate) | minor |
| L3-3 | `TimeStampRequests.java` 532 | a `SignerInfo` signed with RSASSA-PSS (parameters other than absent or an empty `NULL`) | a `DerException`, so the token is reported "cannot be read" (`UNREADABLE`) where `ALGORITHM_UNSUPPORTED` is the cause; the outcome `SIGNATURE_INVALID` is right, the detail is misleading | minor |
| L3-4 | `TimestampTransport.java` 381; `TimestampConfiguration.java` 362; `Rfc3161AnchorProvider.java` 266 | any `toString` | the specification says none of the three carries the URL of the authority; all three render `host=<url host>`; the host is no secret under GDR-NFR-3 | minor (conformance deviates) |
| L3-5 | `Rfc3161AnchorProvider.java` 234-252; `AnchorNotes.java` 264 and 324 (git-store) | a very large note pushed to the notes reference | the provider has no size bound on a stored proof and the store reads a note and a kept proof whole (`getBytes(Integer.MAX_VALUE)`), so the CI verification allocates and parses it whole; push access to the notes reference is needed | minor |
| L3-6 | `TimestampTransport.java` 181 | each `Rfc3161AnchorProvider.from` | builds a new `HttpClient` that neither the provider nor the transport can close; harmless for one long-lived provider | minor |
| L3-7 | `TimestampTransport.java` 202-205, 317-329, 347-358 | a JVM debug switch other than `jdk.httpclient.HttpClient.log`, for example `javax.net.debug` with `ssl:record:plaintext` | whether it prints the `Authorization` header was not shown: the guard covers one switch | not shown by reading |
| info | `Der.java` 349-406, 519-533, 598-604 | hostile DER | confirms: the multi-octet tag is refused, the long-form length is at most 4 octets, minimal and inside the enclosing element (no allocation from an attacker-controlled length), indefinite length and trailing octets are refused, INTEGER minimality and the OID arc bound are checked, there is no recursion | info (confirms) |
| info | `TimeStampTokens.java` 298-448 | a hostile token | confirms: the `messageDigest` is compared in constant time, the `contentType` must be `id-ct-TSTInfo`, each attribute occurs once, the signature covers the attributes under the explicit SET tag, only SHA-256/384/512 are accepted and the signature hash must equal the digest, the signer is chosen by `sid` and bound by every present ESS hash, the extended key usage must be critical and exactly `id-kp-timeStamping` | info (confirms) |
| info | `TimeStampTokens.java` 454-573 | — | confirms: trust anchors only from `timestamp.trust`, the token's `genTime` is the validation date, revocation is not checked (stated), no AIA fetch, the path search is bounded at 16 certificates and 256 paths; a token backdated by a TSA key compromised later verifies, which is inherent without revocation data and stated | info (confirms) |
| info | `Rfc3161AnchorProvider.java` 162-203; `TimestampTransport.java` 183, 198-281 | — | confirms: nonce, SHA-256 imprint and the full token are verified before a proof is returned; only PKIStatus 0 and 1 grant; redirects are never followed; any non-200 is refused; the reply is bounded at 1 MiB with a deadline; the bearer token appears in no detail, log or `toString`; the proof round trip with the store's base64 is consistent | info (confirms) |
| info | `TimeStampTokens.java` 456-478 | — | the open Sonar INFO java:S2143 (`java.util.Date`) is forced: `Date.from(at)` feeds `X509Certificate.checkValidity(Date)` and `PKIXParameters.setDate(Date)`, which accept nothing else; leaving it open for a manual dismissal is the right call | info (confirms) |

**Layer 3 not shown by reading:** L3-7; whether the build and `verify` are green on Java 21 and 25; what the last two commits (round-3
hardening, Sonar gate fixes) changed on top of the squash (read as one diff); the JDK's default hostname check and how PKIX treats a critical
extended key usage on a leaf; how `AnchorVerification` handles an unexpected `RuntimeException` from `verify`; the tests the leaf did not read
(`TimestampConfigurationTest`, `LogMessagesCatalogueTest`, `DomainTermAbsenceTest`, `AiaRetrievalProbe`, most of `TimestampSecretsTest`).

**Layer 3 verdict:** findings — 6 minor (L3-1 to L3-6), none blocking; not shown by reading — 1 (L3-7).

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS `module-info` with the automatic module name | conforms | `module-info.java` 34-42; `git-timestamp/pom.xml` 16 |
| AGENTS.md | `@NullMarked` per package, `@Nullable` where needed | conforms | `package-info.java` 24 |
| AGENTS.md | expected outcomes returned as results, not thrown | conforms | `obtain` and `verify` return `StoreResult`; `DerException` and `Rejected` are private and mapped (`TimeStampTokens` 198-208); the `AnchorVerification` caller of `verify` was not read |
| AGENTS.md | `CuiLogger` with a `LogRecord` holder, catalogued | conforms | `GitTimestampLogMessages` 32-103, `LogMessages.adoc`, `LogMessagesCatalogueTest` (not read) |
| AGENTS.md | Javadoc on every public type and method | conforms | the public types and the factory are documented, everything else is package-private |
| AGENTS.md | tests JUnit 5, `cui-test-generator`, EasyMock | conforms | the doubles are a real in-process authority, so EasyMock does not apply |
| GDR-NFR-3 | no token, password or key in a detail, message, log or metadata | conforms | `Rfc3161AnchorProvider` 266, `TimestampTransport` 381, `TimestampConfiguration` 362; `TimestampSecretsTest`, `Rfc3161ObtainTest` 761-778 (not all read) |
| `git-timestamp.adoc` | no `toString` of the three classes carries the URL | **deviates** | L3-4 |
| AGENTS.md / CLAUDE.md | no dependency without the owner's approval; the module meant to add none | conforms | `git-timestamp/pom.xml` 20-65; `jdk.httpserver` is a JDK module on test compilation only (104-120); JGit's `ObjectId` is used in main code through the transitive `requires` of `git-store` |
| AGENTS.md | third-party dependencies stay out of the lower modules | conforms | `git-core`, `git-store`, `git-testing` untouched |
| GDR-GEN-1 | one layering include per reactor edge in the module's own pom | conforms | `git-timestamp/pom.xml` 70-87, root `pom.xml` |
| plan spec | `git-store` consumed, not changed; `.github/project.yml` untouched | conforms | the 32-file stat |
| plan spec | the touched files stay within the declared Expected Surface | **deviates** | `module-dependency-graph.svg` (L2-4) |
| RFC 3161 2.4.1 / GDR-TSA-1 | SHA-256 imprint over the lowercase hex id, `certReq` TRUE, random nonce, version 1 | conforms | `TimeStampRequests` 107-109, 134-145 |
| RFC 3161 2.4.2, 2.3 / GDR-TSA-2 | one signer, signed attributes, ESS signing certificate, critical sole `id-kp-timeStamping`, PKIX path to `timestamp.trust` | conforms (the `tsa` name rule of 2.4.2 is not implemented, L3-2) | `TimeStampTokens` 210-235, 298-344, 427-448, 454-573 |
| RFC 5652 5.4 | signature over the signed attributes under the explicit SET tag | conforms | `TimeStampTokens` 393-397 |

## Metrics and Anomalies

- Tokens: 34,161,847 over every session. The retrospective measured 7.29M against its 2.5M error threshold for a multi-module feature;
  6-finalize alone used 3.85M (four triage loop-backs).
- Duration: 75,344 s of wall clock (about 20.9 hours).
- Anomalies: over budget by three times; three whole-tree `verify` runs were killed by the learned build timeout with every test green (the
  suite grew from 1,964 to 2,195 tests); the first recorded PR number was 73 (a closed PR of the same branch), corrected afterwards; an atomic
  file write interrupted by a session restart left a temp file; a baseline-drift rebase was run without the refine re-entry, which stayed
  `in_progress`; `sonar fetch_findings` filed one issue five times. All of these are plan-marshall subjects and went to its inbox.

## Routing and Merge Behavior

- Review: CodeRabbit (required) last reviewed `ae27e54`; its free-review quota ran out, so the last two commits (`15afbb5` round-3 hardening,
  `67b17d7` Sonar gate fixes) were never reviewed by it, and the merge barrier counted its earlier review as participation
  (`review_complete_after_handling`). The operator explicitly chose "Merge without it". Sourcery (optional) refused the diff on size (7,871 changed
  lines). The three review numbers were re-read on 2026-11-25 with `runbook_lane review-gate --pr-number 74`: the read is **undecidable**
  (`undecidable_read`: the parser cannot read CodeRabbit's "Minor comments: 16"); `unresolved_review_threads=0` is recorded as not re-verified
  and `review_body_findings_total` and `review_body_findings_handled` are `unknown` in the landing, so they are missing keys, not differences.
- CI/merge: squash-merged through the merge queue as `729ca28`; CI, 2,195 tests and the Sonar quality gate passed on the final commit (the lane's claim).
  The base moved during the run (`e38e079`, three documentation conflicts) and the branch was rebased after the pre-push gate was re-run on the
  new head.
- Sonar: the landing says `sonar_open_new_issues=1`: java:S2143 (INFO, key `AaIN1JmnF1H5L5GKrffy`, `TimeStampTokens`), left open on the operator's
  explicit choice after Sonar refused the dismissal twice with HTTP 400 and no reason. It needs a manual dismissal in the SonarCloud page. This
  analysis did not re-measure it (the Sonar project key is not configured locally); the count agrees with the landing's own residue note.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-17 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-17 --field pr --value 74`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-17 --field landing --value landings/PLAN-17.md`
- [x] row `plan_marshall_plan_id` already stamped (`plan-17-timestamp-rfc3161`)
- [x] `epic.md` narrative reconciled: queue annotation, Decision, Open Defects (incomplete landing; the open Sonar issue; the review gap), Watch (the manual Sonar dismissal)
- [x] resume anchor updated; `queue-view.md` regenerated
- [x] eleven candidate lessons moved to the plan-marshall `lessons-routing` inbox (`libraries-initial-implementation-088` to `-098`), recorded in `lessons-moved.md`

## Follow-Ups

- L1-1, L2-1 to L2-3 and L3-1 to L3-4, L3-6 and L3-7 went into the new container plan **PLAN-26** (`git-timestamp`, held back from `next`),
  because no staged plan edits `git-timestamp`: deliverable 1 the missing tests, deliverable 2 the code defects, deliverable 3 the TLS trust
  statement (decided with the owner at its outline).
- L3-5 (the unbounded read of a stored note and proof) went into **PLAN-25 deliverable 2** (the `git-store` container plan); PLAN-26 deliverable
  2(e) adds the provider's own bound.
- L2-4 and L1-2: recorded here; PLAN-17's spec is spent.
- The open Sonar issue and the three review-barrier facts: Open Defect and Watch in `epic.md`.
