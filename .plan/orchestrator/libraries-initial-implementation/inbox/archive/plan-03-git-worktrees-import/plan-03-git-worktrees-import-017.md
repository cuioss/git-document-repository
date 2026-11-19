envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:16:57Z

# Bootstrap unmeasured build tiers so leaves can run per-deliverable builds

- Component: plan-marshall:manage-architecture
- Category: improvement
- Source: plan-retrospective (plan-03-git-worktrees-import), confidence high

## Context

In phase 5 the focused per-deliverable builds for git-worktrees (`compile` and `module-tests`, i.e.
`test -pl git-worktrees -am`) resolved as `execution_tier: orchestrator` with the reason "Unmeasured;
orchestrator-tier until first measurement". The execute leaves therefore skipped them for deliverables 1, 2 and 3,
relied on the whole-tree `verify -Ppre-commit` instead, and envelope 2 ended with a `verification_handback` for
`verify:module-tests` that the orchestrator then ran itself.

## Root cause

A command that has never been measured is pinned to the orchestrator tier, and a leaf is not allowed to run an
orchestrator-tier command, so a new module's focused build never gets the first measurement from inside a leaf.

## Proposed action

Give an unmeasured command a bounded per_task default (for example the resolved Bash ceiling) on its first run, or
record the measurement from the orchestrator's hand-back run so the next leaf resolves it per_task.

## Evidence

- log_analysis: 5-execute dispatch boundaries include 1 verification_handback
- execution_context_dispatch_audit: firing_comparison 5-execute unpaired execution rows verify:module-tests x2
- chat_history_analysis: envelope-1 and envelope-2 leaves both report the focused builds skipped as orchestrator-tier
