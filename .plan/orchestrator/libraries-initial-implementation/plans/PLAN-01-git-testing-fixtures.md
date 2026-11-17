# PLAN-01: git-testing — dependency management, credential generator, repository fixtures

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Fill the `git-testing` scaffold so every later module can test against it: put the owner-approved foundation
dependencies into the root `pom.xml` dependency management, take over the transport-credential generator from
`pm-provider-git` (GDR-TEST-2), and write the three JUnit 5 repository fixtures of GDR-TEST-1 (local bare repository,
in-memory DFS repository exposed only as `org.eclipse.jgit.lib.Repository`, local remote by `file:` URL). The code is
a plain copy from `~/git/pm-mcp-core` (no history); the commit message names the source repository, path and commit.

## Deliverables

1. Root `pom.xml` dependency management and `git-testing/pom.xml`: JGit 7.8.0 (`org.eclipse.jgit:org.eclipse.jgit`,
   tag `v7.8.0.202609011348-r`), `cui-test-generator`, JUnit 5, EasyMock, `cui-test-juli-logger`, Lombok and
   `cui-java-tools` — all approved by the owner on 2026-11-17 (epic decision). `git-testing` declares JGit,
   `cui-test-generator` and JUnit 5 directly (GDR-GEN-1); `module-info.java` gains the `requires` and, with the first
   public class, `exports` (GDR-GEN-2).
   - Skills: pm-dev-java:java-core, pm-dev-java:java-null-safety
   - Persona: implementer
2. Credential generator (GDR-TEST-2): `GitCredentialGenerator` and `GitGenerators` from
   `pm-provider-git/src/test/java/de/planmarshall/provider/git/testing/generators/`, moved into
   `git-testing/src/main/java/de/cuioss/git/document/testing/` (main scope — the module is consumed in test scope),
   with `GitCredentialGeneratorTest`. Values are random and never real secrets. The generator's credential type must
   not depend on `git-core` (git-testing depends on no reactor module): produce user and token as plain values.
   - Skills: pm-dev-java:java-core, pm-dev-java-cui:cui-testing, pm-dev-java:junit-core
   - Persona: implementer
3. Repository fixtures (GDR-TEST-1): JUnit 5 extensions / parameter resolvers for a local bare repository (temporary
   directory, per test, deleted after it), an in-memory repository wrapping
   `org.eclipse.jgit.internal.storage.dfs.InMemoryRepository` but exposing only `Repository`, and a local remote (a
   second bare repository addressed by a `file:` URL). No network access; every created directory removed. Javadoc on
   every public type and method.
   - Skills: pm-dev-java:java-core, pm-dev-java:junit-core, pm-dev-java:javadoc
   - Persona: implementer
4. Tests of the fixtures themselves (cleanup asserted, in-memory type not leaked, `file:` URL shape) and the
   `doc/specification/git-testing.adoc` / `AGENTS.md` "Current state" update for this module.
   - Skills: pm-dev-java:junit-core, pm-documents:ref-asciidoc
   - Persona: module-tester

## Standing Constraints

- Conventions of `AGENTS.md`: JPMS, `@NullMarked` packages, Javadoc on every public type and method, license header
  via `-Ppre-commit`, tests with JUnit 5 / `cui-test-generator` / EasyMock. Build only through the canonical commands
  in `CLAUDE.md` (`python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`).
- No dependency beyond the approved list above; anything else is put to the owner first.
- Package rename `de.planmarshall.provider.git.testing.generators` → `de.cuioss.git.document.testing`; no `pm-*`
  domain term survives in a public type (GDR-GEN-1).
- Do not touch `.github/project.yml` (release guard, AGENTS.md § Releases).
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. A dismissal the run cannot transmit is a blocker. The merge gate reads the open-finding
  count at the head — `0`, or every remaining key operator-selected; an `undecidable` read blocks.

## Done when

- `verify` passes on the reactor at the plan's head, with `git-testing` exporting the generator and the three
  fixtures, and the fixtures' own tests passing.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review) is `0` or every key
  is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: `git-testing` is a scaffold holding only `module-info.java` and `package-info.java` — read at
  `git-testing/src/main/java/module-info.java`.
- OBSERVED: the generator lives in `pm-provider-git`'s test tree as `GitCredentialGenerator` (36 lines) and
  `GitGenerators` (31 lines) with `GitCredentialGeneratorTest` (73 lines) — read at
  `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/test/java/de/planmarshall/provider/git/testing/generators/` at
  pm-mcp-core `287e1a86`.
- OBSERVED: `GitGenerators` uses Lombok `@UtilityClass` — read at the same path § `GitGenerators`.
- HYPOTHESIS: `GitCredentialGenerator` produces `GitOperations.GitCredential` (a `pm-provider-git` type), so moving it
  unchanged would make `git-testing` depend on `git-core` — confirm/refute at
  `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/test/java/de/planmarshall/provider/git/testing/generators/GitCredentialGenerator.java`
  § `GitCredentialGenerator` (verify-at-outline). Refuted → deliverable 2's plain-value note is moot.
- HYPOTHESIS: `pm-provider-git`'s tests carry no reusable fixture for a local remote; the fixtures of GDR-TEST-1 are
  new code (`ported/PortedFixtures` is worktree-digest specific) — confirm/refute at
  `~/git/pm-mcp-core/pm-providers/pm-provider-git/src/test/java/de/planmarshall/provider/git/ported/PortedFixtures.java`
  § `PortedFixtures` and `JGitOperationsTest` setup (verify-at-outline).
- Verify-first clause: confirm at outline that `cui-java-parent` 1.7.7 does not already manage the JGit / test-stack
  versions (read the effective pom) before adding them to the root dependency management.

## Expected Surface

- OBSERVED: `pom.xml`
- OBSERVED: `git-testing/pom.xml`
- OBSERVED: `git-testing/src/main/java/module-info.java`
- OBSERVED: `git-testing/src/main/java/de/cuioss/git/document/testing/`
- HYPOTHESIS: `git-testing/src/test/java/` (new test tree)
- OBSERVED: `doc/specification/git-testing.adoc`
- OBSERVED: `AGENTS.md`

## Dependencies and Sequencing

- Depends on: none (first plan of the epic).
- Overlaps with: PLAN-02 (root `pom.xml`, `AGENTS.md`) — sequential anyway.
- Adjacent to: `git-core/` — untouched here; git-testing must not depend on it.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-01-git-testing-fixtures.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
