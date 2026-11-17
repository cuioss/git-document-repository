# PLAN-06: Build guards — module layering, git-core-only consumer check, native-image metadata

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Make the layering of GDR-GEN-1 checkable in the build instead of a convention, prove that a consumer of `git-core`
alone receives no third-party dependency of the modules above it, and ship the native-image metadata the foundation
modules need (GDR-NFR-4). The rule choice (Maven Enforcer rule vs an architecture test) is open in
`doc/specification/general.adoc` and is settled by this plan. This is the last plan before the 0.1.0 release.

## Deliverables

1. Layering guard (GDR-GEN-1): a build rule that fails when a module declares a reactor dependency outside the
   dependency graph of `doc/concepts/module-structure.adoc`; written so each later module plan adds one line. The
   choice and its reason are recorded in `doc/specification/general.adoc` (replace "is open"). If the chosen rule
   needs a new plugin or library, put it to the owner first.
   - Skills: pm-dev-java:java-core, pm-dev-java:manage-maven-profiles
   - Persona: implementer
2. Consumer check (GDR-GEN-1): a test consumer project depending on `git-core` alone whose resolved dependency set is
   asserted to hold no third-party artifact of the upper modules (today: proves the shape; it gains teeth as the
   aspect modules arrive). Plus a check that no public type carries a domain term.
   - Skills: pm-dev-java:junit-integration, pm-dev-java:junit-core
   - Persona: integration-tester
3. Native-image metadata (GDR-NFR-4): `META-INF/native-image/de.cuioss/<artifactId>/` for `git-core` and
   `git-worktrees` with the reflection and resource metadata they need (JGit's own metadata considered); the spec keeps
   saying that no native lane verifies it here yet and that `pm-mcp-core`'s native build is the first verification.
   - Skills: pm-dev-java:java-core
   - Persona: implementer
4. Release readiness: `README.adoc` and `AGENTS.md` describe the filled foundation; nothing in `.github/project.yml`
   changes (the 0.1.0 release is an operator act through `/release`).
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter

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

## Done when

- The layering guard fails a deliberately wrong reactor dependency (a test or a recorded verification run) and passes
  at the plan's head.
- `verify` passes at the plan's head.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the root `pom.xml` lists the modules `git-core`, `git-worktrees`, `git-testing` and has no enforcer
  configuration — read at `pom.xml` § `<modules>` (grep for `enforcer` finds none).
- OBSERVED: `doc/specification/general.adoc` § GDR-GEN-1 says the build rule "is open" and § GDR-NFR-4 that "this
  repository does not verify the requirement yet".
- HYPOTHESIS: `cui-java-parent` 1.7.7 already configures `maven-enforcer-plugin` with rules a reactor rule could join
  — confirm/refute at the effective pom (`help:effective-pom`) of `pom.xml` § `maven-enforcer-plugin`
  (verify-at-outline).
- HYPOTHESIS: JGit 7.8.0 ships no GraalVM metadata of its own — confirm/refute at the `org.eclipse.jgit` 7.8.0 jar §
  `META-INF/native-image/` (verify-at-outline).

## Expected Surface

- OBSERVED: `pom.xml`
- OBSERVED: `git-core/pom.xml`
- OBSERVED: `git-worktrees/pom.xml`
- HYPOTHESIS: `git-core/src/main/resources/`
- HYPOTHESIS: `git-worktrees/src/main/resources/`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `README.adoc`
- OBSERVED: `AGENTS.md`

## Dependencies and Sequencing

- Depends on: PLAN-03 and PLAN-05 (all foundation code in place).
- Overlaps with: every module-creating plan later adds to the guard (sequential by queue order).
- Adjacent to: `.github/project.yml` and `.github/workflows/` — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-06-build-guards.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
