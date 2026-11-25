# PLAN-26: git-timestamp hardening (the leftovers of PLAN-17)

epic: libraries-initial-implementation
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Close the test gaps, the code defects and the documentation gaps that the three-layer analysis of PLAN-17 (`landings/PLAN-17.md`,
PR #74, `729ca28`) found in the new module `git-timestamp`, and that no staged plan owns. This is the container plan of the epic for
that module: it was staged on 2026-11-25 under the epic's routing rule (a finding goes into the staged plan that fits by content, and a
container plan per module is staged when none fits), because no staged plan edits `git-timestamp`. **Held back from `next` until the
operator releases it.** Later `git-timestamp` leftovers of landing analyses fold into this spec until it is launched.

## Deliverables

1. Tests that can fail for the verification rules of GDR-TSA-2 (`landings/PLAN-17.md` L2-1, L2-2, L2-3), each first re-read at the plan's
   head (lines are those of `729ca28`). **The test authority builds only well-formed ECDSA/SHA-256 tokens** (`TestTimestampAuthority`
   430-456), so deleting the `messageDigest` equality check (`TimeStampTokens` 327-330) or the `contentType` check (323-326) would leave
   every test green, and so would the single-signer rule, the digest and signature algorithm refusals and the extended key usage rules.
   Extend the test authority to build malformed tokens on demand, and add one test per rule that fails when the rule is removed
   (a mutation by hand at the outline is the check): a wrong `messageDigest`, a wrong `contentType`, a missing or repeated signed
   attribute, two signers (`NOT_ONE_SIGNER`), a SHA-1 and an MD5 digest and signature (`ALGORITHM_UNSUPPORTED`), an extended key usage
   that is not critical, that is missing or that carries a second purpose, and a key usage that contradicts the purpose. Also cover
   the paths no test runs: the RSA verification (`rsaEncryption` with SHA-256, SHA-384 and SHA-512, `TimeStampTokens` 164-177 and
   412-423) and the `sid` by subject key identifier (672-677), with an RSA test PKI beside the EC P-256 one (`TestCertificates` 54), and
   the production transport path: every test injects an `HttpClient` with a test `SSLContext` (`Rfc3161AnchorProvider` 125), so the
   JDK default context, the hostname check and the mapping of a TLS failure to `UNAVAILABLE` are never run; test them through a child
   JVM with a trust store property, as `AiaRetrievalProbe` does, and never against the network.
   - Skills: pm-dev-java:junit-core, pm-dev-java:java-security
   - Persona: module-tester
2. Code defects of the landing (`landings/PLAN-17.md` L3-1 to L3-7, items (a) to (g) in that order), each first re-read at the plan's head, behaviour fixes reproduced by
   a test that fails at `729ca28`: (a) **a signer certificate that is itself in `timestamp.trust` takes the direct branch**
   (`TimeStampTokens.checkPath` 457-464), where only `checkValidity(genTime)` runs, so the JDK's certificate-path algorithm and key-size
   constraints (`jdk.certpath.disabledAlgorithms`) are not applied and a short-key or weak-signature certificate in the trust list is
   accepted while the path branch refuses it: apply the same constraints on both branches (the operator controls the list, so this is
   consistency, not a hole), tested with a trust list that holds a 1024-bit RSA signer; (b) **the `tsa` field of the `TSTInfo` is parsed
   and discarded** (`TimeStampRequests` 463): RFC 3161, 2.4.2 requires that, when present, it corresponds to a subject name of the
   verifying certificate; implement the check or record in `git-timestamp.adoc` that it is not checked and why (no trust impact, the
   trust comes from the certificate), tested either way; (c) an `AlgorithmIdentifier` with parameters other than absent or an empty
   `NULL` raises a `DerException` (`TimeStampRequests` 532), so a `SignerInfo` signed with RSASSA-PSS ends as "cannot be read"
   (`UNREADABLE`) where `ALGORITHM_UNSUPPORTED` is the true cause: map it, tested; (d) the specification says no `toString` of
   `TimestampConfiguration`, `TimestampTransport` or `Rfc3161AnchorProvider` carries the URL of the authority, and all three render
   `host=<url host>` (`TimestampTransport` 381, `TimestampConfiguration` 362, `Rfc3161AnchorProvider` 266): either drop the host or
   correct the sentence in `git-timestamp.adoc` § GDR-TSA-1 (the host is no secret under GDR-NFR-3; decide with the owner because it
   changes a documented statement) and test it; (e) `Rfc3161AnchorProvider.verify` (234-252) has no size bound on a stored proof, so a
   very large note makes the CI verification parse it whole: refuse a proof larger than the reply bound (`MAX_REPLY_BYTES`, 1 MiB,
   `TimestampTransport` 79) before parsing, tested. Re-grounded on 2026-11-25 at `c22700a`: the read of the note itself (`AnchorNotes` 264 and 324 at
   `729ca28`) is now bounded by PLAN-22 at 16 MiB (`ExplicitSave.readBlob`), so the allocation is capped and what remains is parsing up to 16 MiB
   with the provider's own DER reader; keep the 1 MiB bound for consistency with the reply bound or drop the item if the re-read at the outline
   shows the reader bounded enough; (f) every `Rfc3161AnchorProvider.from` builds a new `HttpClient` that neither the provider nor
   the transport can close (`TimestampTransport` 181): decide whether the provider is closable or the client is shared, and record it;
   (g) the credentialed-request guard covers only `jdk.httpclient.HttpClient.log` (`TimestampTransport` 202-205, 317-329, 347-358):
   read which other JVM debug switches print plaintext application data (for example `javax.net.debug` with `ssl:record:plaintext`)
   and either extend the guard or record the limit in the specification; the reading of the leaf did not show what they print.
   - Skills: pm-dev-java:java-security, pm-dev-java:java-core, pm-dev-java:junit-core
   - Persona: implementer
3. Documents of the landing (`landings/PLAN-17.md` L1-1): the specification never says which trust the TLS connection to the
   authority uses. The code uses the JVM default `SSLContext`, not `timestamp.trust` (`TimestampTransport` 181-186), so an authority
   behind a private CA needs the JVM trust store changed and nothing says so. Decide with the owner whether the TLS connection uses
   `timestamp.trust` (a documented behaviour change of GDR-TSA-1) or the JVM default (a recorded rule), and record the decision in
   `git-timestamp.adoc` § GDR-TSA-1 and, if a requirement changes, `general.adoc`.
   - Skills: pm-dev-java:java-security
   - Persona: documenter

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked`
  packages with `@Nullable` where needed, results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a
  `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc
  on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock
  against the `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail,
  exception message, log message or commit metadata; tests assert it with a JUL log capture (`cui-test-juli-logger`).
- Verify every claim about a JGit API against JGit 7.8.1 source or documentation and name the version (CLAUDE.md); the dependency
  update of 2026-11-25 (PR #62) moved the version.
- Every behaviour fix is first reproduced by a test that fails at `729ca28` and passes at the plan's head; an item whose premise the
  re-read at outline refutes is dropped from the deliverable that names it. No new dependency: the module stays on its own DER/CMS
  reader and writer (the owner's decision of 2026-11-24).
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn`
  ad hoc. A whole-tree `verify` has outgrown the learned build timeout: pass the explicit `--timeout` of the ceiling.
- No dependency without the owner's approval (AGENTS.md). Approved: JGit, `cui-test-generator`, JUnit 5, EasyMock,
  `cui-test-juli-logger`, Lombok, `cui-java-tools`. Any other library is put to the owner through the question tool BEFORE it is added.
- `git-store` and the other modules stay read-only; an item that needs a change there is reported in the PR and the inbox message,
  not absorbed (the unbounded note read is PLAN-25's).
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S
  SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The
  lane never records a dismissal of its own; a dismissal the run cannot transmit is a blocker. The open issue java:S2143
  (`AaIN1JmnF1H5L5GKrffy`, `TimeStampTokens`, `java.util.Date` forced by `X509Certificate.checkValidity(Date)` and
  `PKIXParameters.setDate(Date)`) of PLAN-17 still needs its manual dismissal in the SonarCloud page; the gate reads the count at the
  head, and a failed read is `unknown`, never `0`.
- Review lessons of PLAN-17 (`landings/PLAN-17.md`): mask a `byte` with `& 0xFF` before any bitwise operation (java:S3034 failed the
  reliability gate once); the required review bot ran out of quota before the last two commits, so state in the PR which commits it
  did not review.

## Done when

- `verify` passes at the plan's head, and every behaviour fix of deliverable 2 has a test that fails at `729ca28` and passes at the
  head; every rule of deliverable 1 is covered by a test that fails when the rule is removed.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is `0` or
  every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the verification rules of `TimeStampTokens` (`messageDigest` 327-330, `contentType` 323-326) have no test that fails when
  they are removed: the test authority builds only well-formed tokens (`TestTimestampAuthority` 430-456) and `Rfc3161VerifyTest` has no
  case for them — read at `729ca28`.
  - verdict: corroborated | checked_at: 729ca28f8b670943d5b425ffa96934f7fda9f976 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 729ca28: TimeStampTokens 323-330 carries the contentType and messageDigest checks; TestTimestampAuthority 430-456 builds only well-formed ECDSA/SHA-256 tokens with a correct messageDigest; grep of Rfc3161VerifyTest for messageDigest, contentType, NOT_ONE_SIGNER, ALGORITHM_UNSUPPORTED and critical finds none; the only token mutation is the flipped last byte at 168-180
- OBSERVED: a signer certificate that is in `timestamp.trust` takes a branch that runs only `checkValidity` — read at
  `git-timestamp/src/main/java/de/cuioss/git/document/timestamp/TimeStampTokens.java` 454-464 at `729ca28`.
  - verdict: corroborated | checked_at: 729ca28f8b670943d5b425ffa96934f7fda9f976 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 729ca28: TimeStampTokens.checkPath 454-464 returns after signer.checkValidity(date) when trust.contains(signer), before the PKIX branch
- OBSERVED: the TLS connection uses the JVM default context — `TimestampTransport.clientBuilder` (178-186) sets no `SSLContext`, and the
  specification does not say which trust the connection uses.
  - verdict: corroborated | checked_at: 729ca28f8b670943d5b425ffa96934f7fda9f976 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read by the orchestrator at 729ca28: TimestampTransport.clientBuilder 178-186 sets followRedirects, version and connectTimeout and no SSLContext; git-timestamp.adoc names an HTTPS url and the timestamp.trust list for token verification but has no sentence on the trust of the TLS connection (grep of tls, https, truststore, SSLContext)

## Expected Surface

- OBSERVED: `git-timestamp/`
- OBSERVED: `doc/specification/git-timestamp.adoc`
- HYPOTHESIS: `doc/specification/general.adoc` (only if a requirement or the dependency table changes)
- HYPOTHESIS: `git-timestamp/doc/LogMessages.adoc` (only if a log message is added)
- HYPOTHESIS: `AGENTS.md` ("Current state": only if a public type or method changes)

## Dependencies and Sequencing

- Depends on: PLAN-17 (shipped, PR #74, `729ca28`).
- Overlaps with: no staged plan on `git-timestamp/`; `AGENTS.md` and `general.adoc` only when they change.
- Adjacent to: PLAN-25 (`git-store`; the unbounded read of a stored note was bounded by PLAN-22, which narrows deliverable 2(e) here).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-26-git-timestamp-hardening.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
