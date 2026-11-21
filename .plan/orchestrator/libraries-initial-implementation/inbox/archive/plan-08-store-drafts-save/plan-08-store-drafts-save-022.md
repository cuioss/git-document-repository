envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:58:55Z

# Resolve the commit trailer once per plan and pass it to every execute leaf

- Source: plan-retrospective (chat-history analysis), plan-08-store-drafts-save, PR #48
- Component: plan-marshall:workflow-integration-git
- Category: improvement
- Confidence: high

## Context

Four execute leaves (envelopes 1, 2, 4 and 5) each reported the same conflict on their own: the git-workflow trailer resolver gives `Co-Authored-By: plan-marshall <noreply@cuioss.de>`, the session attribution says to end commits with the Claude co-author and session lines, and the orchestrator brief named a different model ("Claude Sonnet 5.5") than the one writing the commits (Opus 5.5). Every leaf chose the session attribution and asked the orchestrator to amend if that was wrong. Nobody resolved it, so all commits on the branch rest on four separate leaf judgements.

## Root cause

The commit trailer is resolved per leaf, at commit time, from three sources that can disagree, and no plan-level decision records which one wins.

## Proposed action

Resolve the trailer once (for example in phase-4-plan or at the start of phase 5), record it in the plan status, and hand the resolved trailer to every execute leaf in its brief. When the trailer resolver and the session attribution disagree, surface it once to the operator instead of letting each leaf decide.

## Evidence

- aspect: chat_history_analysis — envelope 1, 2, 4 and 5 hand-backs each name the trailer conflict
- aspect: logging_gap_analysis — no decision-log entry fixes the trailer for the plan
