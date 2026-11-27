envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T20:01:37Z

# Grant Grep to leaves or route their search to architecture search

- Source: plan-retrospective (chat-history-analysis, permission-prompt-analysis)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:execution-context
- Category: improvement
- Confidence: high

## Context

Four dispatched leaves broke the no-grep-via-Bash rule in this one plan: the refine re-dispatch (request.md headings), the outline q-gate (three git-forms sources), the second triage (StoreIndex public methods) and sonar-roundtrip iteration 4 (`git grep` for the Sonar key). Several other leaves reported "Grep was not available" and read files in pages instead. Every slip was self-reported and read-only.

## Root cause

The harness did not grant Grep to the subagents, and the leaves did not reach for `architecture search --content` first, so the nearest tool was Bash grep.

## Proposed action

Make the leaf's first fallback explicit at the point of use (workflow steps that locate text should name `architecture search --content` or `Read` with offsets), or arrange for Grep to be granted to execution-context leaves on this target.

## Evidence

- refine hand-back: "Slip: I ran one Bash grep"; outline q-gate: "one locate step used Bash grep"; triage: "one Bash grep call"; sonar-roundtrip: "used git grep through Bash"
