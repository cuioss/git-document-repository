envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:39Z

# Resolve a relative WORKTREE when cwd is already the pinned worktree

- Source: plan-retrospective (chat-history-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:execution-context (and plan-marshall:phase-6-finalize prompt bodies)
- Category: bug
- Confidence: high

## Context

The first simplify leaf reported that `compute-footprint` returned `worktree_not_found` for the relative worktree path in its instructions and succeeded only with `--worktree-path .`. Automatic-review iteration 2 and a later simplify re-fire reported the same: the relative WORKTREE did not resolve because cwd was already the pinned worktree, so they fell back to plain git or the absolute path.

## Root cause

Finalize dispatches pass a WORKTREE path relative to the main checkout while the leaf's cwd is pinned to the worktree, so the path is resolved against the wrong base.

## Proposed action

Pass `.` as WORKTREE when the leaf's cwd is pinned to the worktree, or have the leaf resolve a relative WORKTREE against the main checkout root.

## Evidence

- simplify hand-back: "compute-footprint reported worktree_not_found for the relative worktree path ... It succeeded with --worktree-path ."
- automatic-review iteration 2: "the relative WORKTREE path did not resolve because cwd was already the worktree"
- simplify re-fire 3: "The relative WORKTREE path did not resolve from cwd, so I used the absolute worktree path for git"
