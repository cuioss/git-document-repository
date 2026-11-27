# PLAN-28: Protocol-neutral identity — an opaque attestation, the `verifyIdentity` callback, authority and subject

epic: libraries-initial-implementation
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Make the libraries know nothing about how a user was authenticated. Today `git-store` and `git-forms` carry the vocabulary of OpenID Connect
(`IdentityClaims(issuer, authTime, acr, amr)`, the trailers `Actor-Issuer`, `Auth-Time` and `Auth-Context`, the configuration keys `step-up.acr` and `step-up.amr`,
the default value `mfa` of RFC 8176), although `doc/concepts/git-as-document-persistence.adoc` puts the OAuth flows and the identity provider out of scope. This plan
replaces it by two seams and nothing else: **recording**, where the application hands in an opaque attestation (ordered key/value pairs) that the store writes
verbatim as signed trailers after its own `Actor` pseudonym, and **verifying**, where the application passes an `IdentityVerifier` whose `verifyIdentity` the workflow
calls before a transition that requires verification writes anything. The person directory keys a person by `authority` and `subject` instead of `issuer` and `sub`.
The library enforces THAT a verification ran and records its attestation, and never what is verified. It was decided by the operator on 2026-11-27 (epic.md
Decisions): no protocol default (a deployment names what holds, here by supplying the callback), "step-up" and "mfa" are too specific, `TrailHand` is renamed
`Audit-Notary` everywhere. **Next plan to emit after the restart of 2026-11-27**; PLAN-15, PLAN-16, PLAN-25 and PLAN-27 wait for it.

## Deliverables

1. The decision, in the repository's own form: a new ADR (the next free number under `doc/adr/`, written with the `manage-adr` skill) that amends the identity part of
   ADR-0005 (the trailers `Actor-Issuer`, `Auth-Time`, `Auth-Context` and "releases require step-up by default: MFA") and the issuer/`sub` part of ADR-0006, and states the
   rule: the libraries record an application's attestation and call its verifier; they never interpret either. It records the rejected alternatives (neutral names with a
   declarative policy over `authority`, `authenticatedAt`, `assuranceLevel` and `methods`; typed generics through `StoreWriter`) and the trade-offs accepted: the
   trailer schema is the application's convention (documented by `Audit-Notary`, not by these libraries), and GDR-NFR-3 is no longer enforced by the shape of a type but
   stated as the application's duty. ADR-0005 and ADR-0006 stay otherwise as they are; their headers point at the new ADR. Decide at the outline, with the owner, the names
   this deliverable freezes: `Attestation` and `Trailer`, `authority` and `subject`, `IdentityVerifier` and its `verifyIdentity`, the outcome `IDENTITY_NOT_VERIFIED`, the
   workflow key `verify-identity`.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter
2. `git-store`, the recording seam and the person key (GDR-STORE-13, GDR-STORE-19): (a) `IdentityClaims` is replaced by the public **interface** `Attestation` with one method, `List<Trailer> trailers()`
   (`Trailer(key, value)`), which the application MAY implement with its own type so that it carries typed values its verifier reads back (the local login of `Audit-Notary` checks a renewed
   password and needs the time of that check, which is no trailer string to parse); the factory `Attestation.of(Trailer...)` returns a violation as `MALFORMED` naming the component and never the value
   (the convention of `IdentityClaims.of`), and `Attestation.empty()`; the store validates `trailers()` on every call whatever the implementation, and an implementation whose `trailers()` throws is isolated as
   `REFUSED`; `StoreWriter.save` (251, 286) and `release` (336, 362), `ExplicitSave`, `DocumentRelease`, `CloneWrites` and
   `IdentityTrailers` take it. The store checks syntax only: a key in the trailer token form of `git interpret-trailers`, a value on one line without control
   characters, a bound on the number and on the size, no duplicate key, and no key the store writes itself (`Actor`, and the evidence trailer of deliverable 3); it writes the
   attestation verbatim after `Actor`. `Actor-Issuer`, `Auth-Time` and `Auth-Context` are removed from the store. (b) `PersonDirectory.pseudonym(issuer, subject)` (58) and
   `bind(pseudonym, issuer, subject)` (115) become `authority` and `subject`; the documented key is the pair, "because a subject is unique only within its authority"; the
   Javadoc, `StoreProviders` and GDR-STORE-19 say no more about an identity provider, a token or `sub`. (c) The decision to take at the outline: a configuration list
   `commit.required-trailers` of key names only, so that a deployment can insist on a non-empty attestation without the store knowing what a key means (recommended: yes, default empty);
   if taken, a missing key is `MALFORMED` before anything is written. (d) The tests of the 21 test files that build `IdentityClaims` (and the `urn:example:acr:mfa` examples)
   move to `Attestation`; a test per syntax rule, one that the trailers are signed (the signature covers them), one that a save without a pseudonym still ends
   `MALFORMED`, and one that no detail or log names a value (GDR-NFR-3).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: implementer
3. `git-forms`, the verifying seam (GDR-FORMS-5, GDR-FORMS-7): (a) `StepUpPolicy` (all of it: the switch, `amr`, `acr`, `maxAge`, the constant `MFA`), its tests and the `Clock` of
   `DocumentTransitions` and `TransitionRunner` are removed; (b) the public functional interface `IdentityVerifier` with `VerificationResult verifyIdentity(VerificationRequest)`, where
   the request carries the caller's `Attestation`, the author pseudonym, the key of the document or entry, and the transition (name, from, to, mechanism); the result is
   `VERIFIED`, `NOT_VERIFIED` or `UNAVAILABLE` (a verifier that could not decide, for example an unreachable identity provider, is never read as "not verified"); (c)
   `TransitionRunner` (131) calls it exactly once per `perform`, for a transition that requires verification, before anything is written, the resumed release of
   GDR-FORMS-5 included, and outside every lock of the store; `NOT_VERIFIED` is the outcome `IDENTITY_NOT_VERIFIED` (renamed from `STEP_UP_REQUIRED` in `FormsOutcome`,
   `TransitionOutcomes`, `GitFormsLogMessages`, `git-forms/doc/LogMessages.adoc` and `general.adoc`'s outcome table; the detail names no value), `UNAVAILABLE` is the passed-on
   unavailable outcome, and a `RuntimeException` or an `Error` other than a `VirtualMachineError` from the callback is isolated as `FAILED` naming only the class, as every
   other callback of the store (`StoreEvents` 222-227); (d) the workflow key `step-up` becomes `verify-identity` (a boolean; `Transition.stepUp` and `defaultStepUp` become
   `verifyIdentity` and `defaultVerifyIdentity`; a release requires it by default, an `intermediate-save` must not declare it); the requirement stays readable from the public `Transition`
   record before a transition is performed, because the application asks which transitions need verification (the specification of `Audit-Notary` decides it once, in the server, and its
   web app prepares its dialog from it); the application maps `IDENTITY_NOT_VERIFIED` to whatever its own API answers (the consumer's REST and MCP contract names it `STEP_UP_REQUIRED`: its choice, not this library's); (e) fail closed: `DocumentTransitions` is
   refused at construction when a transition of a loaded workflow requires verification and no verifier was passed, so that "no verification" is only ever a verifier the
   application wrote (the switch `step-up.enabled` of PLAN-14 deliverable 6 is superseded by this, and so is the defect L3-6 of its landing); (f) the evidence in the signed
   history: a commit of a transition whose verifier returned `VERIFIED` carries the trailer `Identity-Verification: performed` after the attestation (recommended; decide at
   the outline), so that the history itself shows that a check ran. Tests: the callback is called once and before any write (a recording verifier, a draft and a branch
   unchanged on a refusal), once on a resumed release, never for a transition that does not require it, a refusal writes nothing, `UNAVAILABLE`, a throwing verifier, a missing verifier,
   a verifier that sees the transition and the key (a four-eyes rule written as a test: the approver is not the author), the renamed workflow key and the load refusal of the
   old one, the evidence trailer.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security, pm-dev-java:junit-core, pm-dev-java-cui:cui-testing
   - Persona: implementer
4. The documents and the rename: GDR-STORE-13, GDR-STORE-19, GDR-FORMS-5, GDR-FORMS-7 (renamed "Identity Verification per Transition"), GDR-NFR-3 (the sentence on `sub` and the
   session id becomes the application's duty), `general.adoc`'s outcome table, `git-directory.adoc` (the subject hash over `authority`, a line feed and `subject`; the entry
   holds neither; the first-login and logout lifecycle written as callbacks of the application, no token, `iss`, `sub` or `sid`), `module-structure.adoc` (94), the concepts
   `identity-and-signing.adoc` (the trailer example, the section "Step-Up for Approvals" as "Identity Verification", the open question on claims), `schema-driven-documents.adoc`
   (104-107), `git-as-document-persistence.adoc` (52-54), `directory-and-catalogs.adoc` (80, 110), `data-protection.adoc` (57, 103-105), `doc/README.adoc` and `AGENTS.md`
   ("Current state"); and **`TrailHand` is renamed `Audit-Notary`** where `AGENTS.md` (17-18) and `doc/README.adoc` (17-18) name it, with the repository links
   (`https://github.com/cuioss/Audit-Notary`; `cuioss/TrailHand` redirects there). Keep the addresses of the concepts and specifications the application links to (AGENTS.md § Project).
   **Addresses the consumer links stay stable** (read in the checkout of `Audit-Notary` at `11169dc`): every requirement id (`GDR-STORE-12`, `-13`, `-14`, `-18`, `-19`, `GDR-FORMS-1` to `-9`,
   `GDR-DIR-2`, `-4`, `-6`, `-7`) keeps its number and its `[#id]` anchor (titles may change), and the section anchors `identity-and-signing.adoc#_what_has_to_be_done_right`,
   `schema-driven-documents.adoc#_open_questions`, `directory-and-catalogs.adoc#_open_questions` and `storage-engine.adoc#_two_levels_of_saving` stay; the section "Step-Up for Approvals" is linked
   only from inside this repository (`schema-driven-documents.adoc` 107), so its renamed anchor is updated there. The consumer's own documents are not changed by this plan (report, deliverable 4).
   The sweep that proves the deliverable: a case-insensitive search of `doc/` (outside `doc/adr/`), `AGENTS.md`, `README.adoc` and every `src/main` tree of the reactor for
   `mfa`, `amr`, `acr`, `auth_time`, `Auth-Context`, `Actor-Issuer`, `ID Token`, `OpenID`, `OIDC`, `OAuth`, `step-up`, `StepUp`, `TrailHand`, and for `sub` and `sid` as words, finds nothing that
   concerns identity (the module `git-timestamp` names X.509 issuers and subjects: those are no hits of this rule, and the deliverable lists them).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS (`module-info.java`, `exports` added with the first public class of a package), `@NullMarked` packages with `@Nullable` where needed,
  results instead of exceptions for expected outcomes (GDR-GEN-3), `CuiLogger` with a `LogRecord` holder per module and a documented log-message catalogue for every INFO/WARN/ERROR
  identifier (GDR-GEN-4), Javadoc on every public type and method, license header via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock against the
  `git-testing` fixtures and without network access.
- Secrets (GDR-NFR-3): no token, password, key material, session id, `sub`, e-mail address or person name in a result detail, exception message, log message or commit
  metadata. After this plan the store cannot tell an attestation value from such a datum: no detail, exception message or log line names an attestation value, a verifier's
  message or a verifier's exception message (the class name only), and the specification states that what goes into an attestation is the application's duty.
- The libraries keep no knowledge of the mechanism: no new constant, parameter name, trailer key, configuration key, test fixture or document sentence names a protocol,
  a token claim or a method of authentication. The one exception is the ADR of deliverable 1, which names them to explain what it amends.
- Everything is unreleased (no tag exists; 0.1.0 is the pending first release), so renames and removals need no compatibility shim and no deprecation.
- Verify every statement about an external product against its documentation and name the version (the trailer form of `git interpret-trailers`, for the Git version the CI
  uses); AGENTS.md: every statement about an external product cites its source.
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac` or `mvn` ad hoc.
- No dependency without the owner's approval (AGENTS.md); this plan needs none. Do not touch `.github/project.yml` or any workflow file (every PR that touches the first starts
  the release workflow; AGENTS.md § Releases).
- `git-core`, `git-timestamp` and `git-testing` stay read-only; an item that needs a change there is reported in the PR and the inbox message, not absorbed.
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE OPERATOR'S SELECTION — the question tool names each finding
  key, the progress record records the selection as `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The lane never records a dismissal of its own; a
  dismissal the run cannot transmit is a blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected; an `undecidable`
  read blocks. The three S4449 suppressions in `StoreConfiguration` and the three PLAN-14 suppressions (`LinkTargets`, `TransitionStep`, `DataNode`) carry no
  `operator-selected` record: put them to the operator if a change here touches them.
- Review lessons of PLAN-14 (the epic's local lessons store, `2026-11-27-22-001` to `-007`, and `landings/PLAN-14.md`): when a deliverable rewords a normative statement, search the
  whole documentation tree for the old wording before the affected files are frozen; a consumer-supplied callback is isolated at EVERY call site, with a test per call site
  (changed save, unchanged save, resumed release); a test that keeps code and specification in step checks both directions; read the value of a result only after `isOk()`;
  quote the outcome the isolation code actually returns, never a paraphrase; sync with `origin/main` before every push of a fix round.
- The implementation of a plan that renames a public type touches 21 test files: keep one commit per module so that a review round can follow it, and run the module's `verify`
  before the next.

## Done when

- `verify` passes at the plan's head.
- The behaviour this plan introduces is NEW at `62b23b5` (`main` after the land of PR #82; the old types are present there) and each item has a test that fails at `62b23b5`
  and passes at the plan's head: the verifier is called once before any write and a refusal writes nothing; a missing verifier for a workflow that requires verification is
  refused at construction; a throwing verifier ends the transition as `FAILED` naming only the class; `UNAVAILABLE` is not `NOT_VERIFIED`; the attestation reaches the commit
  as signed trailers after `Actor`, and a malformed attestation is `MALFORMED` without its value; the person directory is keyed by `authority` and `subject`; the evidence
  trailer, when taken, is on a verified transition's commit.
- The existing tests of saves, releases, drafts, transitions, deadlines, typed links and running numbers that do not concern identity hold at `62b23b5` and at the plan's head,
  unchanged but for the type and the parameter they pass.
- The sweep of deliverable 4 finds no identity-related hit outside `doc/adr/`, and `git grep -i trailhand` finds none outside `.plan/`.
- `FormsOutcomeSpecificationTest` and every other list-in-sync test hold in both directions after the rename of the outcome (the requirement numbers of
  `git-forms.adoc` and `git-store.adoc` stay without a gap).
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left open) is `0` or every key is operator-selected; a failed
  read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the claims are read by exactly one checker and one formatter — `StepUpPolicy.holds` reads `claims.acr()`, `claims.amr()` and `claims.authTime()` at
  `git-forms/src/main/java/de/cuioss/git/document/forms/StepUpPolicy.java` 127-132, and `IdentityTrailers` reads `issuer`, `authTime`, `acr` and `amr` at
  `git-store/src/main/java/de/cuioss/git/document/store/IdentityTrailers.java` 78-104; a grep of every `src/main` tree for the accessors finds no other reader, at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: StepUpPolicy.java 127-132 reads acr, amr and authTime; IdentityTrailers.java 78-104 reads issuer, authTime, acr, amr; a grep of the src/main accessors finds no other reader
- OBSERVED: the step-up check has one call site and the `Clock` serves only it — `git-forms/src/main/java/de/cuioss/git/document/forms/TransitionRunner.java` 131
  (`policy.requires(allowed) && !policy.holds(claims, clock.instant())`, before the call is built and before anything is written) and the `Clock` fields of `TransitionRunner` (58)
  and `DocumentTransitions` (133), at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: TransitionRunner.java 131 is the only holds call; the Clock fields are at TransitionRunner 58 and DocumentTransitions 133 and serve only that check
- OBSERVED: the trailers are only written, never read by the libraries — the names `Actor-Issuer`, `Auth-Time` and `Auth-Context` occur in `src/main` only in `IdentityTrailers.java` (48-54)
  and a Javadoc of `StoreWriter.java` (53); the libraries read a commit message only for its subject or as text (`DocumentRelease.java` 600 compares the first paragraph, `AnchorVerification.java` 427
  compares the checkpoint message, `git-core` returns the full message in its log records) and none parses a trailer, and `SignatureVerification` reads the `gpgsig` header, not the message, at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: the trailer names occur in IdentityTrailers.java 48-54 and the StoreWriter Javadoc 53; commit messages are read only for subject comparison (DocumentRelease 600, AnchorVerification 427) and as text in git-core
- OBSERVED: the person directory is keyed by issuer and `sub` in its public signatures — `git-store/src/main/java/de/cuioss/git/document/store/PersonDirectory.java` 58
  (`pseudonym(String issuer, String subject)`) and 115 (`bind(String pseudonym, String issuer, String subject)`), and in GDR-STORE-19 (`doc/specification/git-store.adoc` 1316-1335), at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: PersonDirectory.java 58 pseudonym(String issuer, String subject) and 115 bind(String pseudonym, String issuer, String subject); GDR-STORE-19 at git-store.adoc 1316-1335
- OBSERVED: the identity vocabulary is in documents outside the two specifications — `doc/specification/git-directory.adoc` 43-58, `doc/concepts/identity-and-signing.adoc` 51-68, 90-110 and 169,
  `doc/concepts/schema-driven-documents.adoc` 104-107, `doc/concepts/git-as-document-persistence.adoc` 52-54, `doc/concepts/directory-and-catalogs.adoc` 80 and 110,
  `doc/concepts/data-protection.adoc` 57 and 103-105, `doc/concepts/module-structure.adoc` 94 and `AGENTS.md` 135 — and `TrailHand` is named only in `AGENTS.md` 17-18 and `doc/README.adoc` 17-18, at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 by a grep of doc/ and AGENTS.md: the vocabulary sits in the named lines; TrailHand only in AGENTS.md 17-18 and doc/README.adoc 17-18
- OBSERVED: the concept already puts the mechanism out of scope — `doc/concepts/git-as-document-persistence.adoc` 155 ("the OAuth flows (Token Sheriff, in the application) and the identity
  provider; the libraries only consume credentials"), which the current model contradicts.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: git-as-document-persistence.adoc 155 states the OAuth flows and the identity provider are out of scope
- OBSERVED: no tag exists and 0.1.0 is not released (`git tag` is empty; AGENTS.md § Releases: the first release is a deliberate dispatch), at `62b23b5`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: re-read at cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395: git tag prints nothing; no release exists
- HYPOTHESIS: no consumer reads the trailers or declares the workflow key by the old names beyond what the operator knows — confirm at the workflow files of `cuioss/Audit-Notary`
  (the key `step-up`) and at its code that formats or reads `Auth-Context` (verify-at-outline). Checked on 2026-11-28 in the local checkout of `Audit-Notary` at `11169dc`: its Java code imports only
  `git-core` and the registry of `git-store` (`audit-notary/src/main/java/de/cuioss/notary/audit/service/RepositoryService.java` 13-18) and names no `IdentityClaims`, `StepUpPolicy`,
  `DocumentTransitions` or `PersonDirectory`, so there is no code to migrate; but its specification declares the library's names as the contract (the workflow key `step-up` in the category's
  `workflow.yaml`, `doc/specification/authentication.adoc` AUN-AUTH-4 at 205-209; the trailers `Actor-Issuer`, `Auth-Time` and `Auth-Context` as "the library's trailers", AUN-AUTH-3 at 158-183;
  `STEP_UP_REQUIRED` as the REST and MCP answer, AUN-AUTH-4 at 202 and `web-mock/src/main/resources/scenarios/release.json` 898), and links `GDR-FORMS-7` five times and `GDR-STORE-13` once.
  - verdict: contradicted | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: yes | evidence: read 2026-11-28 in the local Audit-Notary checkout at 11169dc: no Java code uses the identity types, but its specification declares the workflow key step-up, the library's trailers and STEP_UP_REQUIRED as the contract and links GDR-FORMS-7 five times; the spec absorbs it (deliverables 2 to 4 and the stable addresses)
- OBSERVED: the consumer owns the whole mechanism already and the callback fits it — `Audit-Notary` reads `auth_time` and `acr` from the ID token itself
  (`audit-notary/src/main/java/de/cuioss/notary/audit/security/LoginService.java` 233-237, `LoginIdentity.java`, `UserSession.java`), configures `step-up` (`enabled`, `acr`, `amr`, `max-age`) in its
  own `audit-notary.yaml` (AUN-AUTH-4 at 207) and, with local users, makes step-up "a renewed password check of the same user, sent with the transition request" (AUN-AUTH-4 at 195-196), whose time
  is the `Auth-Time` (AUN-AUTH-3 at 176); its ADR-0005 asks the library for a setting that switches the check off (line 162), which the callback makes the application's own decision. At `Audit-Notary` `11169dc`.
  - verdict: corroborated | checked_at: cc5bfb90b4e133d26050d2ea83d3e8bbff1e6395 | by: libraries-initial-implementation/stage | rescoped: n/a | evidence: read 2026-11-28 in Audit-Notary at 11169dc: LoginService.java 233-237, AUN-AUTH-4 195-196 and 207, AUN-AUTH-3 176, ADR-0005 line 162
- HYPOTHESIS: the trailer key rule of `git interpret-trailers` (a token followed by a colon) is what the store should enforce — confirm at the `git-interpret-trailers` documentation of
  the Git version the CI uses and at `git-store/src/main/java/de/cuioss/git/document/store/IdentityTrailers.java` § its header Javadoc (38-39) (verify-at-outline).
- Verify-first clause: at the outline re-read the call sites in the three OBSERVED code claims at the plan's head; a caller of `IdentityClaims` or `StepUpPolicy` this spec does not
  name is added to the deliverable, and a refuted claim loops back to re-scope.

## Expected Surface

- OBSERVED: `git-store/src/main/java/de/cuioss/git/document/store/`
- OBSERVED: `git-store/src/test/java/de/cuioss/git/document/store/`
- OBSERVED: `git-forms/`
- OBSERVED: `doc/adr/`
- OBSERVED: `doc/specification/git-store.adoc`
- OBSERVED: `doc/specification/git-forms.adoc`
- OBSERVED: `doc/specification/git-directory.adoc`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/concepts/identity-and-signing.adoc`
- OBSERVED: `doc/concepts/schema-driven-documents.adoc`
- OBSERVED: `doc/concepts/git-as-document-persistence.adoc`
- OBSERVED: `doc/concepts/directory-and-catalogs.adoc`
- OBSERVED: `doc/concepts/data-protection.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `doc/README.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `git-store/doc/LogMessages.adoc` (only if a log message of the store changes)

## Dependencies and Sequencing

- Depends on: PLAN-14 (shipped, PR #81, `0187cf3`), whose `StepUpPolicy` and workflow key it supersedes, and the land of PR #82 (`62b23b5`).
- **Next plan to emit** (the operator's word of 2026-11-27); not before the restart of that day. It edits `git-store/` and `git-forms/` and the shared documents (`AGENTS.md`,
  `general.adoc`, `git-store.adoc`, `git-forms.adoc`), so the other staged plans on those surfaces run AFTER it, strictly sequential per document: PLAN-25 (`git-store`),
  PLAN-27 (`git-forms`, whose `complete.step-up` and "optional step-up" become `verify-identity`), PLAN-15 and PLAN-16 (`git-directory`, whose person key, login lifecycle and
  `Actor-Issuer` are the vocabulary this plan replaces; they are re-scoped to `authority` and `subject` when this plan ships) and PLAN-24 and PLAN-26 only through the three shared documents.
- Overlaps with: PLAN-25 (the same `git-store/` tree, the release path; no shared file symbol), PLAN-27 (`git-forms/`, the transition code and `general.adoc`'s outcome table),
  and the layer-3 defects of `landings/PLAN-14.md` L3-6 (superseded here) and L3-1, L3-2, L3-7, L3-9 (the resume and the verification order; this plan changes the
  line they cite, `TransitionRunner.java` 131, and does not fix them — re-read their lines at the outline).
- Adjacent to: `git-timestamp`, which names X.509 issuers and subjects and stays untouched; the consumer `cuioss/Audit-Notary`, which must adopt the new types, the workflow key
  and a trailer convention of its own and is reported in the PR and the inbox message, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-28-identity-seams.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
