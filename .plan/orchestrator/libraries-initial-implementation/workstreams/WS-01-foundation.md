# WS-01: JGit foundation — git-core, git-worktrees, git-testing

epic: libraries-initial-implementation

> Charter document for one workstream. See `persona-plan-orchestrator/standards/orchestration-model.md` for the tier
> contract.

## Charter

Fill the three foundation modules with the code taken over from `pm-provider-git` (`~/git/pm-mcp-core`, plain copy,
provenance in the commit message) and extend them by the requirements the moved code does not cover yet: in-core
writing, compare-and-swap, in-core merge, reading, notes (GDR-CORE-4/5/6/9/10), the working-tree status (GDR-WT-3) and
the repository fixtures (GDR-TEST-1). It closes when every `GDR-CORE`, `GDR-WT` and `GDR-TEST` requirement is
implemented and tested, the layering guard of GDR-GEN-1 runs in the build, and the modules are ready for the 0.1.0
release (an operator act through `/release`, not a plan of this workstream).

## Scope

- In scope: `git-core/`, `git-worktrees/`, `git-testing/`, the root `pom.xml` (dependency management, modules, build
  guard), `doc/specification/{general,git-core,git-worktrees,git-testing}.adoc`, `doc/concepts/module-structure.adoc`,
  `doc/clients/pm-mcp-core.adoc`, `AGENTS.md` (Current state).
- Out of scope: every aspect module (WS-02..WS-05); any change in `~/git/pm-mcp-core` (the switch-over is that
  repository's work); the release itself.

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-01-git-testing-fixtures | staged | dependency management, credential generator, the three repository fixtures |
| PLAN-02-git-core-import | staged | hardening, remote policy, identity, results, repository operations, cache repository |
| PLAN-03-git-worktrees-import | staged | linked working trees, commit from a working tree, working-tree status |
| PLAN-04-git-core-incore-write-cas | staged | in-core writing and compare-and-swap |
| PLAN-05-git-core-merge-read-notes | staged | in-core merge, reading, notes |
| PLAN-06-build-guards | staged | layering guard, git-core-only consumer check, native-image metadata |

## Sequencing and Surface Notes

- PLAN-01 first: the git-core and git-worktrees tests use its fixtures (`doc/specification/general.adoc` § Test
  Approach) and it puts JGit into the root dependency management.
- PLAN-02 before PLAN-03 (git-worktrees builds on git-core's `RefusingFS`, `GitResult`, `GitOutcome`) and before
  PLAN-04/05 (they extend `RepositoryOperations` and `GitOutcome`).
- PLAN-03 and PLAN-04 may run side by side: git-worktrees vs git-core, sharing at most the root `pom.xml`.
- PLAN-04 before PLAN-05: notes (GDR-CORE-10) write through the compare-and-swap of GDR-CORE-5.
- PLAN-06 last in the workstream; every later module-creating plan extends its guard.
