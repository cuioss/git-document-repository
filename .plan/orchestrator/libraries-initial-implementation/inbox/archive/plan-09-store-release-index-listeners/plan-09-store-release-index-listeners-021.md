envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:57:51Z

component=plan-marshall:plan-marshall
category=improvement

# Ask refine-identified owner gates before outline runs, not after

Source: plan-retrospective of plan-09-store-release-index-listeners (chat-history and plan-efficiency aspects).

## Context

Refine named two owner gates (the GDR-NFR-1 measuring tool and the step-refusal outcome) and wrote them into the Clarified Request, because the refine leaf cannot ask the operator. The orchestrator did not ask them. Outline then ran in full, batched four owner questions in `outline_prompt`, and had to run a second time with the answers. The operator picked the non-recommended option (JOL), so the second outline run added two affected files and rewrote Dependencies and Risks. 3-outline cost 433K tokens over the two runs.

## Root cause

Owner gates surfaced by refine have no orchestrator hand-off between refine and outline. They wait for outline, which asks them only after a full outline pass built on assumed answers.

## Proposed action

When the refine return names owner gates, the orchestrator asks them (one AskUserQuestion batch) before dispatching outline, and passes the answers into the outline dispatch. Outline keeps its own `outline_prompt` for questions it discovers itself.

## Evidence

- aspect: chat_history_analysis - refine notes "Raise both through the question tool before the dependent work"; outline returned 4 questions; outline re-run to absorb the answers
- aspect: plan_efficiency - 3-outline 433,468 tokens, 12m12s wall
