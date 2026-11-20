envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:25:23Z

component=plan-marshall:manage-architecture
category=improvement
title=Measure a new module's builds so they leave the unmeasured orchestrator tier
source=plan-marshall:plan-retrospective

# Measure a new module's builds so they leave the unmeasured orchestrator tier

## Context

PLAN-07 created the reactor module `git-store`. Every module-scoped build for it (`compile`, `test`/module-tests, `verify` and `verify -Ppre-commit` with `-pl git-store -am`) resolved to `execution_tier: orchestrator` with the hint "Unmeasured; orchestrator-tier until first measurement" for the entire run. Two of the six execute dispatches therefore ended in `verification_handback` (TASK-8 and TASK-15), and execute was re-entered three times, while the whole-tree `verify -Ppre-commit` ran inline and green in the same leaves (847 and 883 tests). The plan finished at 4.7M tokens against the 2.5M error anchor for a multi_module feature plan.

## Root cause

The tier is keyed on a per-module measurement that only a module-scoped run records. A brand-new module has none, and the inline whole-tree runs that already cover it never seed one, so the module stays orchestrator-tier for the plan's whole life.

## Proposed action

Seed or infer a measurement for a module the plan creates: record the module-scoped duration from the first successful whole-tree run that builds the module (or from the first orchestrator-run module build) so later resolves get `per_task`, or let the resolver treat a whole-tree inline run that has already passed on the same tree as satisfying the owed module-scoped step.

## Evidence

- aspect: plan_efficiency - 4,695,078 tokens (floor) vs multi_module+feature error anchor 2.5M; 5-execute 1,782,190 dominant
- aspect: logging_gap_analysis - 5-execute termination causes: 2 verification_handback, 2 budget_yield, 2 clean_exit_queue_empty
- aspect: chat_history_analysis - hand-back reports for TASK-8 and TASK-15 quote "Unmeasured; orchestrator-tier until first measurement" for git-store builds
- aspect: log_analysis - plan-marshall:build-maven:maven 31 calls, 39.7 percent of script time
