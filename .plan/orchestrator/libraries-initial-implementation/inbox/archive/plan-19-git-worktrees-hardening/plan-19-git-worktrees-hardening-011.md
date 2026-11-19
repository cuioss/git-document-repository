envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:57:23Z

# Candidate lesson: defer the criteria freeze until pending outline questions are answered

- Source: plan-retrospective (chat-history, plan-efficiency, log-analysis); work log ERROR 2026-11-18T20:27:47Z (hash 408b1b). lessons-capture saw this record but left it out of its signal populations ("pickup can add it").
- Suggested component: plan-marshall:phase-3-outline
- Suggested category: improvement
- Confidence: high

## Context

The first phase-3-outline dispatch wrote the outline, froze 16 success criteria, transitioned to 4-plan, and only then returned four operator questions, each with a recommended answer, saying "run phase-3-outline once more only if the operator picks a different option". The operator overrode two recommendations (a new public `releaseBranchReservation` operation, and `WORKTREE_NOT_FOUND` instead of `IDENTITY_MISMATCH`). The second dispatch revised the outline to 6 deliverables, but `snapshot-criteria` refused with `refreeze_requires_decision`, so the orchestrator had to put a fourth question (authorize re-freeze) to the operator and dispatch the outline a third time just to freeze and transition.

## Root cause

The criteria snapshot is taken at the first outline approval even when that outline still carries open operator questions whose answers can change the deliverables. Any non-recommended answer therefore turns into a refreeze decision the operator has to give separately.

## Proposed action

In phase-3-outline, do not freeze criteria (and do not transition 3-outline) while `outline_prompt.questions` is non-empty; freeze after the answers are applied. Alternatively, treat the operator's answers to the outline's own questions as the refreeze authorization for the revision they cause, so no extra prompt is needed.

## Evidence

- 3-outline was dispatched 3 times and used 731,687 tokens (24% of the plan), the second-largest phase.
- Phase transitioned 4-plan -> 3-outline at 20:23:19Z after the answers; ERROR refreeze_requires_decision at 20:27:47Z; third dispatch 20:28:34Z only ran snapshot-criteria and the transition.
- Operator decision turn: "May they be re-frozen against the revised outline?" = "Authorize re-freeze".
