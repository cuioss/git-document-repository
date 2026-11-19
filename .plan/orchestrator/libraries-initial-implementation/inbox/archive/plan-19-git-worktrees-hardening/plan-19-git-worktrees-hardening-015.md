envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:57:58Z

# Candidate lesson: give phase-5 a per-deliverable commit trigger that fires on linear task chains

- Source: plan-retrospective (chat-history); envelope 2 hand-back, "Deviations to check"
- Suggested component: plan-marshall:phase-5-execute
- Suggested category: improvement
- Confidence: medium

## Context

Phase-4 produced a strict chain of 10 tasks (each depends on the previous one, because deliverables 1-4 share the same three files). The executing agent reported that the documented commit rule ("commit when no pending task depends on the finished task") never fires on such a chain, so it committed at each deliverable boundary on its own judgement, as earlier commits on the branch did. The result was right, but it rests on agent judgement rather than on the workflow.

## Root cause

The commit trigger is phrased in terms of the task dependency graph. A chain in which every task depends on its predecessor never satisfies it until the last task, which would collapse the whole plan into one commit.

## Proposed action

State the per-deliverable commit trigger in terms of deliverables: commit when the last task of a deliverable is done (all tasks carrying that deliverable number), independent of cross-deliverable dependencies.

## Evidence

- Envelope 2 hand-back: "the documented rule ... never fires on this linear task chain. I committed at each deliverable boundary instead".
- phase-4 hand-back: "The 10 tasks form a strict chain"; execution_order has 10 single-task groups.
