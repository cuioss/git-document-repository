envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:59:19Z

# Escalate fix tasks that edit a module the outline declared read-only

- Source: plan-retrospective (request-result alignment, manifest decisions, artifact consistency), plan-08-store-drafts-save
- Component: plan-marshall:phase-6-finalize
- Category: improvement
- Confidence: high

## Context

The outline Summary said "with no git-core edit", and the module mapping marked git-core read-only. The review-fix tasks from the finalize loop-backs then changed the git-core public API: TASK-14 added `FetchInput.prune`, and TASK-20 made `JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS` public. They also changed `RepositoryOperations.java`, two git-core tests and `doc/specification/git-core.adoc`. The leaves reported this as "decisions you may want to check", but no operator question was raised and `references.json` was not updated. The retrospective found 7 realized paths that no deliverable declared (rule M6 `declared_vs_realized_set` failed).

## Root cause

Fix-task creation in the finalize triage and the execute leaves do not check a fix's target files against the read-only modules in the module mapping, so crossing that boundary is not treated as a scope change.

## Proposed action

When the triage creates a fix task, or a leaf is about to edit a file in a module the module mapping marks read-only, raise a prompt-required envelope (or at least log a decision and add the paths to `references.json`). This matters most for public API changes in a lower module.

## Evidence

- aspect: manifest_decisions — declared_vs_realized_set fail, 7 realized-but-undeclared paths (4 under git-core, git-core.adoc, 2 git-store test helpers)
- aspect: request_result_alignment — scope creep of 7 files against the outline's "no git-core edit"
- aspect: artifact_consistency — references_only set of 7 forwarded to the manifest check
