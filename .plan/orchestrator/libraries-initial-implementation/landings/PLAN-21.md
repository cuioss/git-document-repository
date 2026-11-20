# Landing Analysis: PLAN-21 — merge git-worktrees into git-core as a package, and adapt all documentation

epic: libraries-initial-implementation
workstream: WS-01
pr: #46 (https://github.com/cuioss/git-document-repository/pull/46), merged through the merge queue as `9e3cfb0` (squash, parent `dd57475`)

> Landing record for one shipped plan. Written by the `analyze` verb from the inbox message `plan-21-merge-worktrees-into-core-008`
> after verifying it against git, the CI abstraction and the merged code. The message is a lead, never a fact.

## Deliverable Fidelity vs Spec

Diff read: `git diff -M dd57475 9e3cfb0` — 33 paths, +660/-935. The range `2075640..9e3cfb0` holds one more commit, `dd57475` (#45, the operator's: the application documentation `doc/app/**` moved to `cuioss/TrailHand`, plus two files of `.plan/project-architecture/` and part of the `AGENTS.md` and `doc/README.adoc` text), which does not belong to the plan. Every one of the 33 paths traces to a deliverable. `.github/project.yml` and every `pom.xml` dependency list are untouched; the root `pom.xml` only drops `<module>git-worktrees</module>`.
Of the spec's Expected Surface every touched entry is declared; the hypothesis entry `doc/adr/` was borne out (ADR-0001 and ADR-0004 changed, no new ADR, see below). Nothing undeclared was touched.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Move the code and tests into `git-core` | shipped-as-specified, with the log ids and the requirement ids decided at the outline | `git-core/src/main/java/module-info.java` 24-25 exports `de.cuioss.git.document.core` and `…core.worktrees`; four main classes and `package-info.java` (`@NullMarked`) and six test classes moved by `git mv` (renames at 93 to 99 % similarity; the only non-package source change is the S4449 null-check, below); `GitWorktreesLogMessages` merged into `GitCoreLogMessages` (INFO 2-4 `WORKTREE_ADDED`, `WORKTREE_REMOVED`, `BRANCH_RESERVATION_RELEASED`; WARN 102-104 `WORKTREE_CAPABILITY_REFUSED`, `REFLOG_NOT_WRITTEN`, `ROLLBACK_INCOMPLETE`; templates unchanged; no duplicate id, `GitCoreLogMessages.java` 56-127) and the catalogue in `git-core/doc/LogMessages.adoc` 20-55; `GitOutcome` Javadoc says "the worktrees package" |
| 2. Build configuration | shipped-as-specified | `git-worktrees/` and its POM removed; `git-core/pom.xml` dependency set unchanged, `consumer-isolation` lines 77-115 untouched, surefire `useModulePath=false` and the Lombok processor were already present and identical; `DomainTermAbsenceTest` 61-109 now fails when an exported package holds no scanned public type |
| 3. Specifications | shipped-modified (requirement ids renumbered at the operator's answer) | `git-core.adoc` 609-859 holds § Working Trees with the requirements as `GDR-CORE-12` to `GDR-CORE-15` (anchors at 650, 738, 776, 820; numbering `GDR-CORE-1..15` without a gap); `git-worktrees.adoc` deleted; `general.adoc` names the package in the summary, GDR-GEN-1, the suffix list, the GDR-GEN-3 table, GDR-NFR-3 and GDR-NFR-4 |
| 4. Concepts, decisions, client, index, root documents | shipped-modified (no new ADR: ADR-0004 rewritten in place and ADR-0001 adapted, at the operator's answer) | `module-structure.adoc`, the redrawn module graph SVG (six modules, three solid), `pm-mcp-core.adoc` (anchor renamed, xrefs follow), `doc/README.adoc`, `README.adoc`, `AGENTS.md` (table, Current state, the Enforcer sentence now names `git-store/pom.xml`); a repository content search for `git-worktrees`, `de.cuioss.git.document.worktrees`, `GDR-WT`, `GIT_WORKTREES` and `GitWorktreesLogMessages` outside `.plan/` finds nothing |

The lane reports 4 deliverables and 4 done, as the spec. Not shown by the diff and recorded as unverified plan facts: the reactor test count 883 to 882 (one `@Test` is deleted, the duplicate `DomainTermAbsenceTest` of the removed module, so the number is plausible by reading), the green `verify`, `consumer-isolation` and `module-layering` passing (the PR merged through the merge queue under the required check `build / conclusion`, which is the corroborated CI fact).
**The owner decisions of the outline differ from the spec in three points**, all taken at the operator's answers relayed to the outline: the requirement ids were renumbered (the spec said they stay), the log ids INFO 2-4 / WARN 102-104 keep the worktrees refusal as the separate record `WORKTREE_CAPABILITY_REFUSED`, and ADR-0004 was rewritten in place with no new ADR (the spec said a new ADR). The spec's Done-when text "every moved test keeps its `GDR-WT-n` display name" and "the root pom lists `git-core` and `git-testing` only" (it predates `git-store`) therefore no longer describe the landing; the code and tests follow the decisions, and the three differences are info, not defects. The decision to rewrite an Accepted ADR in place carries no dated record of its reason in the ADR itself (info).
`merge_state=merged` and the merge commit `9e3cfb04b1a02246b826266b54f6095033ec80f2` are corroborated by `ci pr view` (state merged, base `main`). The queue squash message was not set by the run: the commit message on `main` carries the PR body including the CodeRabbit release-notes block (four hits for "coderabbit" in `git log -1 9e3cfb0`, as in `9bdc5ba`).

**The four review-and-Sonar numbers were re-read from the provider.** The landing wrote `unresolved_review_threads`, `review_body_findings_total` and `review_body_findings_handled` as `unknown`; `runbook_lane review-gate --pr-number 46` shows 0 unresolved threads and 1 review body finding (CodeRabbit, inline-1) with no disposition recorded (`merge_allowed: false, reason: body_findings_undisposed`). The landing's own narrative says triage handled all four findings (three CodeRabbit, one Sourcery) and none was pending at merge; that is a lead for `review_body_findings_handled`, which only the run's progress file reproduces (a plan-marshall plan has none under the runbook parser), so it is **not re-verified**, and the single undisposed body finding the parser counts is not evidence that the landing lied. `sonar_open_new_issues=0`: the Sonar read for PR 46 returned `count_status: undecidable` (`head_revision_unavailable`, analysis revision `6b45edb`, the fix commit of the S4449 loop-back), so the number is **not re-verified**, and not a difference.

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf, and the orchestrator re-read the points below at their file and line at the head (`9e3cfb0`). By reading: no build, no test run, no write.

- **Dispatch:** one read-only `execution-context-level-3` leaf for this landing (resolved level `level-3`, `orchestrator.analyze` surface) — reason: a move of 10 files with 93 to 99 % renames, a log-id merge, a requirement renumbering and eleven documents is only checked by comparing the old and the new text, which is the depth the dispatch rule exists for, and one landing's read must not share context with the reconciliation of the seven lessons drained in the same scan.
- **Verdict:** layer 1 — all claims settled, none refuted, three differences between spec and owner decisions (info), one not shown; layer 2 — no blocker, no major, one minor (identical template under two log ids, a deliberate decision), residues info; layer 3 — no defect found.
- **Re-read by the orchestrator:** `git-core/src/main/java/module-info.java` 22-26; `GitCoreLogMessages.java` 51-126 (ids 1-4 and 100-104, templates); `JGitWorktreeOperations.java` 487-502 (the S4449 fix: `existing.getObjectId()` read once into `existingId`, the same two refusals, `StartPoint.of(existingId)`) and 962-971 (the `WORKTREE_CAPABILITY_REFUSED` log call, text and argument unchanged); `DomainTermAbsenceTest.java` 61-109; `git-core.adoc` anchors at 609 and 650 to 820; the repository-wide `git grep` for the old names at the head (empty outside `.plan/`); `AGENTS.md` module table 25-30; `git-core/pom.xml` 77-80; `.plan/project-architecture/` for the old module name (the check below). **Not re-read** (leaf-only, recorded as unverified leads): the per-line renaming diffs of the five moved test classes beyond the sampled `JGitWorktreeOperationsTest` display names, the SVG coordinates, the xref text of `pm-mcp-core.adoc` 104-216, and the JGit-side question of NFR-4 (whether a command the package drives reaches a translation bundle beyond `JGitText`).

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | Claims 0 to 8: the pre-plan observations (modules, file set, dependencies, duplicate test names, log-id collisions, `GitOutcome` text, imports, `.github`/`.claude` free of the name, ADR status) | checked | confirms | the leaf re-read each at `a558afd` / `dd57475`; claims 0 to 8 carry a corroborated verdict already, so the settlement is recorded as a duplicate and persists nothing |
| L1-2 | Claim 9 (hypothesis): the moved package has no reflection, resource or `ServiceLoader` access | checked | confirms | search of `git-core/.../core/worktrees/` empty for `Class.forName`, `getResource`, `ServiceLoader`, `ResourceBundle`, reflection, `MethodHandle`, `Proxy`, `getClassLoader`; the JGit-side half of GDR-NFR-4 is not shown (L1-4) |
| L1-3 | Claim 10 (hypothesis): the merged `git-core` still satisfies `consumer-isolation` | checked | confirms | dependency set and allow-list unchanged (`git-core/pom.xml` 14-66, 77-115); the build result is the merge under the required check |
| L1-4 | GDR-NFR-4: the JGit commands the package drives reach no further translation bundle than `JGitText` | not shown by reading | none | asserted in `general.adoc` 298-311, not shown; `NativeImageMetadataTest` was not run by the reading |
| L1-5 | The spec's Done-when and Standing Constraints against the owner decisions | checked | refutes (the text is superseded, not the claims) | `GDR-WT-n` display names and "no new ADR" are not what the spec says; see Fidelity. Info |

Persisted onto the spec (`corpus set-verdict`): claims 9 and 10 corroborated at `9e3cfb0`. Claims 0 to 8 already carried a corroborated verdict, so no second write was made (equal strength, the first recorded stands). Claim 11 (the verify-first clause) is a procedure, not a claim.

**Layer 1 verdict:** findings — 4 info; 1 not shown by reading (L1-4)

### Layer 2 — Implemented

| Finding | Severity | Claim | Evidence |
|---------|----------|-------|----------|
| L2-1 | minor | `CAPABILITY_REFUSED` (101) and `WORKTREE_CAPABILITY_REFUSED` (102) carry the identical template "Refused capability %s" under two ids, so a log consumer cannot tell them apart by text; deliberate (the owner kept the record separate and the spec says "texts stay"), not a defect | `GitCoreLogMessages.java` 96-110 |
| L2-2 | info | the Tests section of `git-core.adoc` still says "`AbsenceContractsTest` asserts …" for the core test while a second `AbsenceContractsTest` now lives in the worktrees package | `git-core.adoc` 861-873 |
| L2-3 | info | the `<description>` of `git-core/pom.xml` reads awkwardly after "on JGit" | `git-core/pom.xml` 14 |
| L2-4 | minor | the committed architecture inventory `.plan/project-architecture/` still names the removed module: `_project.json` (2 hits), `git-document-repository/enriched.json` (1) and a whole `git-worktrees/enriched.json` (2) remain at the head. It is derived data (regenerated by the steward, not by this plan) and `.plan/` is outside the spec's content search, so no Done-when is broken; the two files #45 changed there are not part of this PR | `git grep` at `9e3cfb0`, `.plan/project-architecture/` |
| L2-5 | info | the reactor count 883 to 882, a green `verify`, `consumer-isolation`, `module-layering`, `NativeImageMetadataTest` and the Sonar read of 0 are not shown by reading | — |

Every moved test keeps its assertions: only the package, the imports, the `DisplayName` requirement ids and the log record names changed (`JGitWorktreeOperationsTest` 372-1801; the INFO count by prefix at 1800 holds, the scenario logs no clone).

**Layer 2 verdict:** findings — 2 minor, 3 info; the build and Sonar facts not shown by reading

### Layer 3 — Correct

No defect found in the moved code. The move is behaviour-neutral: the one non-package source change is the S4449 fix (`existingBranchStart`, 493-501: the same branch structure and outcomes, `NOT_FOUND` on a null id, one read of `getObjectId()`), and the one changed log call (`WORKTREE_CAPABILITY_REFUSED`, id 102) keeps its text and argument. Client-visible differences beyond the package: the artifact `git-worktrees` and its automatic module name are gone, `GitWorktreesLogMessages` and the prefix `GIT_WORKTREES` are gone (ids now `GIT_CORE-2..4` and `102..104`), and a consumer of `git-core` now gets the worktrees package exported; no result, outcome or message text changed, and nothing was released. The pre-existing layer-3 defects of PLAN-19 (L3-1 to L3-8) were moved unchanged and stay PLAN-20's deliverables 1 and 2; their claims were re-read and re-stamped at `9e3cfb0` in this act.

**Layer 3 verdict:** findings — none; no blocker, no major, no minor

### Conformance with the project's rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| AGENTS.md | JPMS, `@NullMarked`, exports with the first public class of a package | conforms | `module-info.java` 24-25; `worktrees/package-info.java` keeps `@NullMarked` |
| AGENTS.md | expected outcomes are results, not thrown | conforms | `JGitWorktreeOperations.java` 962-971 unchanged in behaviour |
| AGENTS.md | `CuiLogger`, one `LogRecord` holder per module, a catalogue for every INFO/WARN/ERROR id | conforms | `GitCoreLogMessages` is the single holder; `git-core/doc/LogMessages.adoc` 20-55 matches the code |
| AGENTS.md | Javadoc on every public type and method; licence header | conforms | the new records carry Javadoc; moved files show no header change |
| AGENTS.md | no dependency added without approval; lower modules free of third-party dependencies | conforms | `git-core/pom.xml` dependency list identical; the allow-list untouched |
| AGENTS.md | a module adds its edge to `module-layering` in its own POM | conforms | `git-store/pom.xml` keeps its edge; `AGENTS.md` now points at it |
| AGENTS.md | do not touch `.github/project.yml` | conforms | absent from the diff |
| AGENTS.md / `general.adoc` | `DomainTermAbsenceTest` covers every exported package (GDR-GEN-1) | conforms | `DomainTermAbsenceTest.java` 61-109 asserts it and can fail |
| `general.adoc` | GDR-NFR-3: no secret in a log message | conforms | the moved templates carry a commit id or a refused capability only |
| `general.adoc` | GDR-NFR-4: the package has no reflection/resource access, so `git-core`'s metadata covers it | not shown by reading | own code clean (L1-2); the JGit side and `NativeImageMetadataTest` not run (L1-4) |
| Plan constraint | no assertion weakened or removed (one duplicate class deleted) | conforms | five moved test classes diffed |
| Plan constraint | Sonar open findings fixed or operator-selected | not shown by reading | the S4449 site is fixed (493-501); the Sonar read is undecidable on a merged PR |

## Metrics and Anomalies

- Tokens (landing fact `total_tokens`): 14,188,567 across the run; wall 10,740 s (about 3.0 h); model `claude-sonnet-5-5`, `effort` unknown; 13 finalize steps all `done`, one Sonar loop-back (iteration 1 of 3, TASK-006); branch cleanup `merged` through the merge queue. Against PLAN-07 (17.2 M tokens, 5.9 h) a documentation-heavy move with one loop-back cost four fifths of the tokens and half of the wall time.
- Cost drivers recorded by the lane: the outline re-entry that re-froze the success criteria on its own authority (the operator approved it afterwards; lesson `2026-11-20-07-001`), a Sonar `java:S4449` finding in a moved file that cost a whole loop-back (lesson `2026-11-18-07-007`), the simplify and quality-gate re-fires for a three-line commit (same lesson), and an automatic-review rate-window wait of about 35 minutes for a review that was not owed (lesson `2026-11-18-13-005`).
- Anomalies: the landing is incomplete by `landing-check` (4 keys: `effort`, `unresolved_review_threads`, `review_body_findings_total`, `review_body_findings_handled`; `cleanup_owed` and `sonar_open_new_issues` are now supplied), recorded as an Open Defect. The footprint-capture residue the retrospective names (10 rename sources read as unrealized, recall 76.7 %) is lesson `2026-11-20-17-001`; the commit trailer of `6b45edb` names "Claude Opus 5.5" while the session ran on Sonnet 5.5 (merged, not rewritten).

## Routing / Merge Behavior

Merged through the merge queue as a squash; the squash message carries the PR body (see Fidelity). The operator's #45 (`dd57475`, the application documentation moved out) merged first; the plan rebased onto it with no collision. Nothing else ran concurrently. PLAN-08, staged behind this plan by the operator's choice, shares three files with it (`AGENTS.md`, `doc/README.adoc`, `doc/specification/general.adoc`) and is now emittable.

## Reconciliation Actions

- Queue: PLAN-21 transitioned to `shipped`; `pr` 46, `landing` `landings/PLAN-21.md`, `plan_marshall_plan_id` `plan-21-merge-worktrees-into-core`.
- Spec corpus: PLAN-21 claims 9 and 10 corroborated at `9e3cfb0`; PLAN-20 re-grounded in the same act — the five worktrees claims (0 to 4) re-read and re-stamped at `9e3cfb0`, the worktrees paths, spec references, Expected Surface, Done-when base and sequencing text moved to the merged package, deliverable 2 extended with item (e) (L2-2, L2-3), deliverable 7 item (g) reduced to the note that the fourth domain-term copy is gone.
- Lessons (7 candidate messages): six are recurrences of existing lessons (`2026-11-20-07-001`, `2026-11-18-18-001`, `2026-11-18-07-007` twice, `2026-11-18-13-004` ninth plan, `2026-11-18-13-003`, `2026-11-18-13-005`) and one is new (`2026-11-20-17-001`, the realized footprint drops rename sources); a second new one is filed from the landing residue (the squash message carries the bot release-notes text).
- epic.md: the PLAN-21 entries (queue annotation, decision), the incomplete-landings entry extended to PLAN-21, a Watch for the stale `.plan/project-architecture` inventory (L2-4), the `GDR-WT` / `GIT_WORKTREES` names in the narrative marked as superseded.
