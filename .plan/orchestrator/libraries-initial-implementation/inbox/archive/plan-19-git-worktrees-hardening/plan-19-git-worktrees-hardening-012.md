envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:57:31Z

# Candidate lesson: report plan-wide task totals in the execute completion status line

- Source: plan-retrospective (logging-gap-analysis, log-analysis); work log 2026-11-18T21:22:12Z (hash b5c8d5) and 21:59:03Z (hash ac6908)
- Suggested component: plan-marshall:phase-5-execute
- Suggested category: bug
- Confidence: high

## Context

After envelope 2 finished every remaining task, phase-5-execute logged `Execute phase complete — 4/10 tasks done`. After the loop-back fix envelope it logged `Execute phase complete — 1/11 tasks done`. In both cases all tasks were done (10/10, then 11/11).

## Root cause

The numerator is the number of tasks completed by the current dispatch (envelope), while the denominator is the plan's total task count. On any multi-envelope or loop-back run the line reads as an incomplete phase.

## Proposed action

Compute the numerator from the task store (tasks with status done) or label the two numbers separately, for example `11/11 tasks done (1 in this envelope)`.

## Evidence

- Envelope 2 hand-back: tasks_completed 4, tasks_remaining 0, loop_exit_guard "0 pending, 10 done".
- Fix envelope hand-back: tasks_completed 1, tasks_remaining 0; plan has 11 tasks, all done.
