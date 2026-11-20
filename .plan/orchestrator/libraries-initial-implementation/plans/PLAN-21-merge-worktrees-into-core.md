# PLAN-21: Merge git-worktrees into git-core, and adapt all documentation

epic: libraries-initial-implementation
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off. This spec is SELF-SUFFICIENT:
> the emitted command is a one-line pointer and carries no brief. See
> `persona-plan-orchestrator/standards/orchestration-model.md` for the tier and hand-off contract.

## Objective

Remove the module `git-worktrees` and make its code a package of `git-core`, before the first release. The module adds no
third-party dependency of its own, it is about 1,700 lines against `git-core`'s 5,000, the shared result type
`GitOutcome` already carries its outcomes, and every plan so far paid for the second module again (a second Enforcer
block, a second domain-term test, a second specification, a second set of build and metadata decisions). Artifact
coordinates are cheapest to change before 0.1.0, which is released with all modules together and cannot be withdrawn.
The change is a move: no operation changes its behaviour, and every document that names the module, its artifact, its
package or its specification is adapted in the same plan.

## Deliverables

1. Move the code and the tests into `git-core` (`git mv`, so the history follows): the main sources of
   `git-worktrees` (`WorktreeOperations`, `JGitWorktreeOperations`, `LinkedWorktrees`, `package-info.java`) go to the new
   package `de.cuioss.git.document.core.worktrees`, which `git-core`'s `module-info.java` exports in addition to
   `de.cuioss.git.document.core`; its seven test classes go to the same package under `git-core/src/test/java`. The
   package keeps `@NullMarked`; the public type names stay as they are, so the only change a client sees is the package.
   `GitWorktreesLogMessages` is merged into `GitCoreLogMessages` (one `LogRecord` holder per module, AGENTS.md): its
   three INFO and its WARN messages take the next free `GIT_CORE-<n>` identifiers inside the CUI ranges, their texts stay,
   and the catalogue of `git-worktrees/doc/LogMessages.adoc` is merged into `git-core/doc/LogMessages.adoc` (nothing is
   released, so no log consumer depends on the `GIT_WORKTREES-<n>` form). Every Javadoc and comment that says
   `git-worktrees` — the `GitOutcome` constants "Raised by `git-worktrees`" and the class Javadoc included — says the
   worktrees package instead. Two test classes have the same simple name in both modules (`AbsenceContractsTest`,
   `DomainTermAbsenceTest`); in the sub-package they do not clash and `AbsenceContractsTest` is moved as it is, while the
   worktrees copy of `DomainTermAbsenceTest` is deleted, because the
   `git-core` scan walks the whole main output and therefore sees both packages (see deliverable 2).
   - Skills: pm-dev-java:java-core, pm-dev-java:javadoc
   - Persona: implementer
2. Build configuration: the module `git-worktrees` is removed from the root `pom.xml` `<modules>` and its directory, its
   `pom.xml` and its Enforcer block are deleted. `git-core/pom.xml` keeps its dependencies unchanged — the removed module's
   own were JSpecify, `cui-java-tools` and Lombok (`provided`), all already declared, and the same test dependencies — and
   the two build sections (Surefire `useModulePath=false`, the Lombok annotation processor, the module-path comment) are
   diffed so nothing the removed `pom.xml` configured is lost. The `module-layering` rule of the root `pom.xml` stays,
   with no include other than `git-testing` in scope `test` (each later module adds its own edge, AGENTS.md); the
   `consumer-isolation` allow-list of `git-core` stays and must still pass with the merged code. `DomainTermAbsenceTest`
   of `git-core` is extended so that it asserts it found at least one public type of each exported package (otherwise a
   merged package could drop out of the scan unnoticed), and `NativeImageMetadataTest` stays as it is. The number of
   tests the reactor executes is recorded before the move (at the plan's base) and after it: the difference is exactly
   the deleted worktrees copy of `DomainTermAbsenceTest`.
   - Skills: pm-dev-java:java-core, pm-dev-java:manage-maven-profiles
   - Persona: implementer
3. Specifications: `doc/specification/git-worktrees.adoc` is merged into `doc/specification/git-core.adoc` as a part
   "Working Trees" (its overview, scope and the four requirements `GDR-WT-1` to `GDR-WT-4`, with their anchors and their
   text; the requirement ids stay, so the `DisplayName` of every test, every Javadoc and every cross reference keeps
   resolving, and the numbering stays free of gaps within each prefix) and the old document is deleted;
   `doc/specification/general.adoc` is adapted wherever it names the module: the summary of which module implements what,
   GDR-GEN-1 (the module list and the dependency graph, the Enforcer rule text, the domain-term test, the third-party
   dependency list), the module suffix list, the GDR-GEN-3 table ("raised by" cells name the package, the sentence "the
   outcomes of `git-worktrees` included"), GDR-NFR-3 and GDR-NFR-4 (the metadata statement: the worktrees package is part
   of the `git-core` artifact and has no reflection, resource or resource-bundle access, which is re-read in the main
   sources, so `git-core`'s metadata covers it and no sentence about a second module remains). Every statement about an
   external product keeps its cited source; nothing unverified is added.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation
   - Persona: documenter
4. Concepts, decisions, client, index and the root documents: `doc/concepts/module-structure.adoc` (the module table and
   its State column, the dependency graph and the sentence that `git-worktrees` and `git-store` build on `git-core`, the
   foundation-modules section — the working trees become a part of `git-core`, with the reason that the document store
   does not need them recorded as a property of the package, not of a module — and the order section);
   `doc/resources/diagrams/module-dependency-graph.svg` (redrawn through `pm-documents:ref-svg-diagrams`, with its
   AsciiDoc embedding and alt text checked); a new ADR, written through `plan-marshall:manage-adr`, that records the
   decision (the context above, the alternatives: keep the module, or merge it; the consequences: one artifact fewer, the
   package boundary replaces the module boundary, the ADR-0001 rule "documents are written without working trees" is
   kept as a rule of the `git-store` code and its dependency on the worktrees package is not allowed), with
   ADR-0004's list of foundation modules and ADR-0001's sentence that `git-worktrees` stays available for consumers
   that need files marked as amended by it, in the form the ADR conventions of the repository use; `doc/clients/pm-mcp-core.adoc`
   (the client depends on `git-core` only, the inventory and the switch-over steps that name two modules, the table of
   what moves where); `doc/README.adoc` (the specification index and the implemented-requirements paragraph, which keeps
   `GDR-WT-1` to `GDR-WT-4` as requirements of `git-core`); `README.adoc` (the module table and the native-image
   sentence); `AGENTS.md` (the module table, the "Current state" paragraph, the build hints and the sentence about the
   Enforcer includes that points at `git-worktrees/pom.xml`). A content search of the repository for `git-worktrees`
   (outside `.plan/`, the new ADR and the amended ADRs) finds nothing, and every link, anchor and cross reference
   resolves.
   - Skills: pm-documents:ref-asciidoc, pm-documents:ref-documentation, pm-documents:ref-svg-diagrams
   - Persona: documenter

## Standing Constraints

- A move, not a change of behaviour: no operation of the worktrees code changes its result, its outcome or its message
  text; the public API differs from today only by the package and by the log identifiers. A defect found while moving
  is not fixed here: it is reported through the plan's inbox message and joins PLAN-20. No test assertion is weakened or
  removed (the one deleted test class is the duplicate named in deliverable 1).
- Decided by the owner (2026-11-20): the new package is `de.cuioss.git.document.core.worktrees`, a sub-package of the module's
  own package (kept encapsulation of the package-private `LinkedWorktrees`, and the package boundary the ADR relies on). The
  flat form and the second package `de.cuioss.git.document.worktrees` were considered and are not to be asked again.
- Decisions taken in this spec and put to the owner through the question tool at the outline, in this order: (1) keeping
  the requirement ids `GDR-WT-1` to `GDR-WT-4` inside `git-core.adoc` (the alternative renumbers them `GDR-CORE-12` to
  `GDR-CORE-15` and rewrites every test name and cross reference); (2) renumbering the log identifiers under `GIT_CORE`;
  (3) a new ADR instead of rewriting accepted ones. A different answer changes the affected deliverable, not the plan.
- Conventions of `AGENTS.md`: JPMS (`module-info.java`, the module name `de.cuioss.git.document.core` stays), `@NullMarked`
  packages, results instead of exceptions (GDR-GEN-3), `CuiLogger` with one `LogRecord` holder per module and a documented
  catalogue for every INFO/WARN/ERROR identifier (GDR-GEN-4), Javadoc on every public type and method, the license header
  via `-Ppre-commit`, tests with JUnit 5, `cui-test-generator` and EasyMock against the `git-testing` fixtures.
- Secrets (GDR-NFR-3): the moved log messages keep carrying nothing the requirement forbids.
- Verify every claim about a JGit API against JGit 7.8.0 and name the version (CLAUDE.md); the documents keep citing the
  sources they were checked against.
- No dependency without the owner's approval (AGENTS.md): this plan adds none and removes none from `git-core`.
- Do not touch `.github/project.yml` (every PR that does starts the release workflow; AGENTS.md § Releases): a search
  finds no module name in `.github/`, and the plan keeps it that way. The 0.1.0 release is an operator act through `/release`
  and is not part of this plan.
- Build only through the canonical commands in `CLAUDE.md` (compile, `verify -Ppre-commit`, `verify`); never call `javac`
  or `mvn` ad hoc. The documentation deliverables additionally pass `verify -Ppre-commit`.
- Sonar is configured for this project: an open Sonar finding is fixed in a pushed commit, or DISMISSED ONLY ON THE
  OPERATOR'S SELECTION — the question tool names each finding key, the progress record records the selection as
  `operator-selected: {issue-key}: {reason}`, and the key is transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition
  {falsepositive|wontfix}`. The lane never records a dismissal of its own; a dismissal the run cannot transmit is a
  blocker. The merge gate reads the open-finding count at the head — `0`, or every remaining key operator-selected;
  an `undecidable` read blocks.

## Done when

- `verify` passes on the reactor at the plan's head; the root `pom.xml` lists `git-core` and `git-testing` only, no
  `git-worktrees` directory exists, and `git-core`'s `module-info.java` exports `de.cuioss.git.document.core` and
  `de.cuioss.git.document.core.worktrees`.
- The number of tests the reactor executes at the plan's head equals the number at the base minus the one deleted
  duplicate (`DomainTermAbsenceTest` of `git-worktrees`), both numbers recorded in the plan's progress; every moved test
  keeps its assertions and its `GDR-WT-n` display name.
- `consumer-isolation` and `module-layering` pass at the head; the `git-core` artifact resolves the same compile and
  runtime closure as at the base.
- A content search of the repository for `git-worktrees` and for `de.cuioss.git.document.worktrees` (outside `.plan/`,
  the new ADR and the two amended ADRs) finds nothing, and the AsciiDoc link and anchor check over `README.adoc`, `doc/`
  and `AGENTS.md` reports no unresolved reference.
- The Sonar open-finding read at the head (open new-code issues plus security hotspots to review, and the rules left
  open) is `0` or every key is operator-selected; a failed read is `unknown`, never `0`.

## Claim Labels

- OBSERVED: the root `pom.xml` lists the modules `git-core`, `git-worktrees`, `git-store` and `git-testing` — read at `pom.xml` § `<modules>`
  at `ed2abde`.
  - verdict: corroborated | checked_at: a558afd517fda67e394ac4f249a5259a0007a305 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: re-read at a558afd: root pom.xml <modules> lists git-core, git-worktrees, git-store, git-testing (lines 51-54); git-store was added by PLAN-07 (9bdc5ba)
- OBSERVED: `git-worktrees` holds four main classes plus `package-info.java` and `module-info.java`
  (`GitWorktreesLogMessages`, `JGitWorktreeOperations`, `LinkedWorktrees`, `WorktreeOperations`), seven test classes
  (`AbsenceContractsTest`, `DomainTermAbsenceTest`, `GitWorktreeListCompatibilityTest`, `JGitWorktreeOperationsTest`,
  `LinkedWorktreesTest`, `WorktreeOperationsRecordsTest`, `WorktreeStatusTest`) and `doc/LogMessages.adoc` — read with
  `git ls-files git-worktrees` at `ed2abde`; main about 1,688 lines against `git-core`'s about 4,976.
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git ls-files git-worktrees re-read: 4 main classes, package-info, module-info, 7 test classes, doc/LogMessages.adoc; main lines counted 1688 against 4976 for git-core
- OBSERVED: the dependencies of `git-worktrees` are `git-core`, JSpecify, `cui-java-tools`, Lombok (`provided`) and, in test
  scope, `git-testing`, JUnit 5, `cui-test-generator` and `cui-test-juli-logger`; `git-core` declares each of the
  non-reactor ones already — read at `git-worktrees/pom.xml` § `<dependencies>` and `git-core/pom.xml` at `ed2abde`.
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git-worktrees/pom.xml dependencies read in full: git-core, jspecify, cui-java-tools, lombok (provided), git-testing, junit-jupiter x3, cui-test-generator, cui-test-juli-logger; git-core declares each non-reactor one
- OBSERVED: `AbsenceContractsTest` and `DomainTermAbsenceTest` exist in both modules under the same simple name, in
  different packages — read at `git-core/src/test/java/de/cuioss/git/document/core/` and
  `git-worktrees/src/test/java/de/cuioss/git/document/worktrees/` at `ed2abde`.
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git-core/src/test/java/de/cuioss/git/document/core/ lists AbsenceContractsTest.java and DomainTermAbsenceTest.java; git-worktrees has both under its own package
- OBSERVED: the log identifiers collide by number, not by prefix: `GitWorktreesLogMessages` declares `GIT_WORKTREES` INFO 1 to 3 and
  WARN 100 and following, `GitCoreLogMessages` declares `GIT_CORE` INFO 1 and WARN 100 and following, each prefix with its
  own catalogue — read at both `LogMessages` holders and `git-core/doc/LogMessages.adoc`, `git-worktrees/doc/LogMessages.adoc`.
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: identifiers read in both holders: GIT_CORE 1, 100, 101; GIT_WORKTREES 1, 2, 3, 100, 101, 102; each catalogue under its own prefix
- OBSERVED: `GitOutcome` carries the worktree outcomes and its Javadoc says "Raised by `git-worktrees`" — read at
  `git-core/src/main/java/de/cuioss/git/document/core/GitOutcome.java` (23, 98-118).
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: GitOutcome.java re-read: class Javadoc line 23 and the constants at 98-118 say Raised by git-worktrees
- OBSERVED: the main code of `git-worktrees` imports only public types of `git-core` (`RefusingFS`, `GitResult`, `GitOutcome`,
  `RepositoryOperations.Identity` and `CommitInfo`) besides JGit — read by `git grep` over the imports at `e43c7e9`.
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git grep of the imports of git-worktrees main: only public core types (RefusingFS, GitResult, GitOutcome, RepositoryOperations.Identity and CommitInfo) besides JGit
- OBSERVED: no file under `.github/`, `.claude/` or `CLAUDE.md` names `worktree`; the documents that do are `README.adoc`,
  `AGENTS.md`, `doc/README.adoc`, `doc/concepts/module-structure.adoc`, `doc/concepts/storage-engine.adoc`, `doc/clients/pm-mcp-core.adoc`,
  `doc/specification/general.adoc`, `doc/specification/git-worktrees.adoc`, the ADRs 0001 and 0004 and
  `doc/resources/diagrams/module-dependency-graph.svg` — read by `git grep -i worktree` at `ed2abde` (the hits in
  `storage-engine.adoc` are about the git command, not the module).
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git grep -i worktree over .github, .claude, CLAUDE.md finds nothing; the document list re-derived by git grep -c at ed2abde
- OBSERVED: ADR-0004 lists `git-core`, `git-worktrees` and `git-testing` as the foundation modules (its metadata, `affects` and
  the decision list) and has the status Accepted; ADR-0001 says `git-worktrees` stays available for consumers that need files —
  read at `doc/adr/0004-…adoc` (9, 11, 29) and `doc/adr/0001-…adoc` (52).
  - verdict: corroborated | checked_at: ed2abde8b471c21a593da23d93567dbac8c9ac6f | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: ADR-0004 metadata lines 9 and 11 and decision line 29 list git-worktrees, status Accepted (line 17); ADR-0001 line 52 says git-worktrees stays available
- HYPOTHESIS: the main code of `git-worktrees` has no reflection, `getResource` or `ServiceLoader` access, so `git-core`'s
  native-image metadata covers the merged package — confirm/refute by a search of the moved package for `Class.forName`,
  `getResource`, `ServiceLoader` and `ResourceBundle` (verify-at-outline; the PLAN-06 analysis searched the main sources
  of both modules and found none).
  - verdict: corroborated | checked_at: 9e3cfb04b1a02246b826266b54f6095033ec80f2 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: three-layer leaf grep of git-core/.../core/worktrees/ at 9e3cfb0 finds no Class.forName, getResource, ServiceLoader, ResourceBundle, reflection, MethodHandle, Proxy or getClassLoader; the JGit side (a translation bundle beyond JGitText reached through CommitCommand, DirCacheCheckout, Status, hooks) is not shown by reading and NativeImageMetadataTest was not run by the reading
- HYPOTHESIS: the merged `git-core` still satisfies the `consumer-isolation` allow-list, because the moved code needs no
  artifact `git-core` does not already declare — confirm/refute by the `verify` run of the reactor (verify-at-outline).
  - verdict: corroborated | checked_at: 9e3cfb04b1a02246b826266b54f6095033ec80f2 | by: libraries-initial-implementation/analyze | rescoped: n/a | evidence: git-core/pom.xml dependency set unchanged and consumer-isolation allow-list lines 77-115 untouched at 9e3cfb0 (leaf reading, re-read by the orchestrator); PR #46 merged through the merge queue under the required check build / conclusion
- Verify-first clause: the numbers above are those of `ed2abde`; every OBSERVED claim is re-read at the plan's own HEAD at
  outline, and a refuted one is dropped from the deliverable that names it. The set of documents that name the module is
  derived again by a content search at the plan's HEAD, not taken from this list.

## Expected Surface

- OBSERVED: `pom.xml`
- OBSERVED: `git-core/pom.xml`
- OBSERVED: `git-worktrees/`
- OBSERVED: `git-core/src/main/java/`
- OBSERVED: `git-core/src/test/java/`
- OBSERVED: `git-core/doc/LogMessages.adoc`
- OBSERVED: `doc/specification/git-core.adoc`
- OBSERVED: `doc/specification/git-worktrees.adoc`
- OBSERVED: `doc/specification/general.adoc`
- OBSERVED: `doc/concepts/module-structure.adoc`
- OBSERVED: `doc/resources/diagrams/module-dependency-graph.svg`
- OBSERVED: `doc/clients/pm-mcp-core.adoc`
- OBSERVED: `doc/README.adoc`
- OBSERVED: `README.adoc`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `doc/adr/` (the new ADR, and the amendment notes of ADR-0001 and ADR-0004)

## Dependencies and Sequencing

- Depends on: PLAN-06 (the foundation and its build guards are in place; PLAN-19 and PLAN-04, whose code and
  documents this plan moves, have shipped).
- Runs before: PLAN-20 (it edits the moved sources, tests and documents; the orchestrator re-grounds its paths, names and
  surface when this plan lands) and the 0.1.0 release (an operator act, not a queue row).
- Sequencing against PLAN-07: PLAN-07 has shipped (PR #41, `9bdc5ba`; main is `a558afd`), so the reactor now has four modules
  (`git-core`, `git-worktrees`, `git-store`, `git-testing`) and the documents this plan rewrites already describe `git-store`.
  Its pieces that touch this plan: `AGENTS.md` says "`git-worktrees` and `git-store` each append their one edge" (the guard
  sentence), "see `git-worktrees/pom.xml`" (the Conventions bullet on a new module's edge, whose example becomes
  `git-store/pom.xml`) and lists `DomainTermAbsenceTest` for four modules (it becomes three); `general.adoc` GDR-GEN-1 and the
  module dependency graph carry the `git-store` edge. The plan's scope is unchanged: it moves `git-worktrees`, not `git-store`,
  and the `git-store` module keeps its own `module-layering` include (`git-core`) untouched.
- Overlaps with: PLAN-08 (three files: `AGENTS.md`, `doc/README.adoc`, `doc/specification/general.adoc`; the operator chose this plan first on 2026-11-20, so PLAN-08 is emitted after it lands) and PLAN-20 on every document and POM named in the Expected Surface. No other staged plan
  names a `git-worktrees` path.
- Adjacent to: `.github/project.yml` and `.github/workflows/` — untouched.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/libraries-initial-implementation/plans/PLAN-21-merge-worktrees-into-core.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the orchestrator owns every other ledger
write — and reports its outcome through its PR and its inbox message. The inbox exception's qualifiers and the sole
sanctioned write mechanism are stated in `persona-plan-orchestrator/standards/orchestration-model.md` § Ledger
Write-Boundary.
