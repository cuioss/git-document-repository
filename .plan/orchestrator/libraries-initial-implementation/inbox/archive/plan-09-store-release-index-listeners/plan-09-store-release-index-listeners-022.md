envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:57:55Z

component=plan-marshall:phase-4-plan
category=bug

# Do not chain tasks across deliverables; it defeats the per-deliverable commit

Source: plan-retrospective of plan-09-store-release-index-listeners (chat-history aspect, envelope 1 hand-back).

## Context

Phase 4 created 9 tasks as one strict chain: every task depended on the one before it, including the first task of each deliverable on the last task of the previous deliverable (TASK-3 on TASK-2, TASK-5 on TASK-4, and so on). The execute commit rule commits a deliverable at its chain tail. Read literally, the rule never fired for deliverable 1, because TASK-2 had a dependent (TASK-3) in another deliverable. The executor worked around it by treating a cross-deliverable dependency as non-blocking for the commit, and logged that decision.

## Root cause

The planner encoded deliverable order as task dependencies, so the chain tail of a deliverable is never the end of a chain.

## Proposed action

Order deliverables through execution order or envelope packing, not through `depends_on` edges between tasks of different deliverables. Add only real data dependencies across deliverables. Alternatively, define the commit rule as "last task of the deliverable done", ignoring dependents in other deliverables, and state it in the execute workflow so leaves do not each improvise it.

## Evidence

- aspect: chat_history_analysis - 4-plan return: "parallelizable_groups: 9 (the deliverables form a strict chain)"; envelope 1: "Read literally, the commit rule would never fire for deliverable 1"
