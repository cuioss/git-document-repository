# PLAN-15: git-directory — module, catalogs, person directory git, reference resolution

epic: libraries-initial-implementation
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Create `git-directory` (`de.cuioss.git.document.directory`, on `git-forms`) with the catalogs of roles and teams plus
application-declared catalogs, retired never deleted (GDR-DIR-1), the default person directory `git` implementing
`PersonDirectory` of `git-store` with person entries `persons/<pseudonym>.yaml`, the subject hash and random
`p-` pseudonyms (GDR-DIR-2), and resolution of `<catalog>:<id>` references and pseudonyms incl. former members
(GDR-DIR-4).

## Deliverables

1. Module creation: pom, `module-info.java` (`provides` the `git` `PersonDirectory`), `LogRecord` holder, root
   `<modules>`, layering guard entry (`git-directory` → `git-forms`), `AGENTS.md` / `module-structure.adoc` state;
   the `git-directory` outcome list (entry required, not bindable).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety, pm-dev-java-cui:cui-logging
   - Persona: implementer
2. Catalogs (GDR-DIR-1): `catalog/roles.yaml`, `catalog/teams.yaml` (optional `parent`), configured application
   catalogs (list file or one file per entry), common fields, retirement; the catalog-resolution interface of PLAN-14
   implemented. Which branch a catalog change is saved on stays an open question of
   `doc/concepts/directory-and-catalogs.adoc` — ask the owner if the implementation forces an answer.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
3. Person directory `git` (GDR-DIR-2): entries in `directory.persons.repository`, bound-entry fields, subject hash =
   SHA-256(issuer LF sub) lowercase hex, no issuer or `sub` stored, pseudonym `p-` + 26 Crockford base32 chars over
   128 bits of `SecureRandom`; entries read into memory at start and after fetches that move `main`, indexed by
   subject hash and pseudonym.
   - Skills: pm-dev-java:java-core, pm-dev-java:java-security
   - Persona: implementer
4. Resolution (GDR-DIR-4) and tests: catalog references incl. retired, role-in-team → pseudonyms, pseudonym → entry or
   former-member marker; tests asserting no `sub` or name leaks outside the entry (GDR-NFR-3).
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
- The person entry is the one file that holds a name (GDR-NFR-3); nothing else the directory obtains is written to a
  repository except pseudonyms (GDR-STORE-19).

## Done when

- `verify` passes at the plan's head with GDR-DIR-1/2/4 each covered by a test naming the requirement id, and the
  `git` provider selectable as `directory.persons.provider`.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the subject-hash and pseudonym formats are specified in `doc/specification/git-directory.adoc` §
  GDR-DIR-2; the branch of a catalog change is an open question of `doc/concepts/directory-and-catalogs.adoc` § Open
  Questions.
- HYPOTHESIS: the Crockford base32 encoder written for ULIDs in PLAN-08 can be reused for pseudonyms without a
  `git-directory` → `git-store` internal-package dependency — confirm/refute at the encoder type PLAN-08 lands in
  `git-store/` and its `module-info.java` exports (verify-at-outline).
  - verdict: contradicted | checked_at: c1e02fced1353da9ad953ab7a32c1a7eb44f1f39 | by: libraries-initial-implementation/analyze | rescoped: no | evidence: re-read at c1e02fc: the Crockford encoder of Ulid.java is private static (132 encode, 47 ALPHABET) and the module exports only de.cuioss.git.document.store with public Ulid.next() and isValid(); no public base32 encoder exists, so a pseudonym (p- plus 26 characters, git-directory.adoc 47) cannot be built from git-store without a new public type or a second encoder in git-directory

## Expected Surface

- OBSERVED: `pom.xml`
- HYPOTHESIS: `git-directory/` (new module — not a top-level entry at HEAD, so the surface reader reports it
  unresolved; the overlap with the other WS-04 plans is enforced by queue order, not by the gate)
- OBSERVED: `doc/specification/git-directory.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `README.adoc` (module paragraph: `git-directory` is created)
- HYPOTHESIS: `doc/README.adoc` (Specification section: which requirements are implemented)

## Dependencies and Sequencing

- Depends on: PLAN-14 (link / catalog-resolution interface, all of git-forms), PLAN-07 (`PersonDirectory`).
- The Crockford encoder is not reusable from `git-store` (the hypothesis of the Claim Labels was contradicted at `c1e02fc`):
  PLAN-08's `Ulid` keeps `encode` private and exports only `Ulid.next()` and `Ulid.isValid(...)`. `git-store` is consumed,
  not changed, so this plan carries its own small encoder in `git-directory` for the `p-` pseudonym (checked against the
  format of GDR-DIR-2 by a test); a public encoder in `git-store` is a store gap reported through the inbox, not absorbed.
- Overlaps with: PLAN-16 — strictly sequential.
- Adjacent to: `git-store/` and `git-forms/` — consumed, not changed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-15-directory-catalogs-persons.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
