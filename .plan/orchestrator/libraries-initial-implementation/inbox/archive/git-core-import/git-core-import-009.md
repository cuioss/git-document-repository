envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:49:39Z

# Fire the chain-tail commit at deliverable end on linear task chains

component=plan-marshall:execute-task category=bug source=plan-retrospective plan=git-core-import

## Context

Plan git-core-import had a linear dependency chain: TASK-1 to TASK-8, each task depending on the one before it,
across four deliverables. Execute envelopes 1 and 2 both reported that the chain-tail check ("no pending task depends
on the finished task") never fires for a task such as TASK-2 or TASK-6, because the next deliverable's first task
depends on it. Read literally, the rule would have produced no commit until the end of the whole chain. Both envelopes
departed from the rule on their own and committed at the end of each deliverable (ce6378a6, 51ec4dde, b6363c85,
24a64ba3).

## Root cause

The commit predicate is keyed on task dependencies, not on deliverable completion. On any chain that crosses a
deliverable boundary, a deliverable's last task always has a pending dependant.

## Proposed action

Commit when every task of a deliverable is done (the deliverable boundary), or treat a dependant in a different
deliverable as not blocking the chain-tail. Add a fixture with a linear cross-deliverable chain.

## Evidence

- aspect: chat_history_analysis: envelope 1 "Read literally it never fires for TASK-2, because TASK-3 depends on it";
  envelope 2 "the chain-tail check would have skipped the commits, because task 7 depends on task 6"
- aspect: request_result_alignment: one commit per deliverable, all five deliverables fulfilled
