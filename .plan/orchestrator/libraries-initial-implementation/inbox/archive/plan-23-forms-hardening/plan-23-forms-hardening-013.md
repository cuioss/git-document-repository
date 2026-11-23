envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:16:46Z

# Scope the per-task quality gate instead of whole-tree verify on every task

Component: plan-marshall:manage-execution-manifest. Category: improvement. Confidence: high. Source: plan-retrospective.

## Context

At 4-plan, compose logged `per_task_gate_appended — appended the resolved gate command to 18 task verification list(s)`. Each task then ran the whole-tree `verify -Ppre-commit` (1402 to 1461 tests). plan-23-forms-hardening made 44 maven calls that own 79.7 % of all script time (5,578 s, about 93 min). Leaves repeatedly skipped the per-deliverable focused build and logged a judgement decision that the green whole-tree gate on the identical tree already covered it.

## Root cause

The per-task gate stamp appends the project-wide quality gate to every task regardless of the task's module, and the per-deliverable build is not suppressed when an identical-tree superset run already passed.

## Proposed action

Stamp a module-scoped gate (for example `verify -Ppre-commit -pl {module} -am`) on each task and keep the whole-tree gate for the end-of-execute sweep; or let the build wrapper report a tree-hash cache hit so a redundant build is skipped by fact.

## Evidence

- aspect: plan_efficiency - 44 maven calls, 79.7 % of script time
- aspect: log_analysis - script_cost_rollup plan-marshall:build-maven:maven 44 calls, 5578420 ms
- aspect: manifest_decisions - decision log per_task_gate_appended to 18 task verification lists
- aspect: llm_to_script_opportunities - 4 logged focused-build skip decisions
