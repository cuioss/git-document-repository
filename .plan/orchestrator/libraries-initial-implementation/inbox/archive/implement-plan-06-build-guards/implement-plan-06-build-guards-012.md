envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:21:10Z

# Amend the outline when a deliverable is dropped mid-execute

Component: plan-marshall:plan-marshall (category: improvement, confidence: high)

## Context

In 5-execute, TASK-8 found that the decided empty-array `resource-config.json` for git-worktrees is invalid under GraalVM's schema. After research the operator chose "Ship nothing", and the orchestrator removed TASK-008 and TASK-009 with `manage-tasks remove`. `solution_outline.md` was never amended: Deliverable 7, its three declared files and the Summary sentence "git-worktrees ships the same files with empty arrays" still read as live scope.

## Root cause

Dropping a deliverable is done by removing its tasks; there is no step or verb that marks the deliverable de-scoped in the outline and references, and no decision line is required when it happens.

## Proposed action

When an operator decision drops a deliverable after 3-outline, mark it de-scoped in `solution_outline.md` with the reason (or provide a `manage-solution-outline` verb that does so and removes its tasks in one call), drop its paths from `references.json`, and log a `[DECISION]` line.

## Evidence

- aspect: artifact-consistency - task_deliverable_match fail, "Deliverables without matching task: [7]"; 3 outline_only paths
- aspect: manifest-decisions - declared_vs_realized_set_mismatch (3 declared-but-unrealized, 1 realized-but-undeclared)
- aspect: logging-gap-analysis - work.log carries only the two MANAGE-TASKS removal lines at 2026-11-20T06:33
- aspect: request-result-alignment - D7 missed by deliberate decision, outline not amended
