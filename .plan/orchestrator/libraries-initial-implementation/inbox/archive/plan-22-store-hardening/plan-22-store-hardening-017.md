envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:34Z

Candidate lesson (retrospective: operator deviation widened scope, declared footprint not re-synced)

component=plan-marshall:phase-5-execute
category=improvement
title=Re-sync the declared footprint when an operator deviation widens scope

## Context

In 5-execute the operator answered two deviation prompts against the recommendation: "Change git-core" (TASK-3 item b) and "Add a parameter" to the public StoreWriter.open (item h); a later triage answer widened git-core again (TASK-13). The leaf added some paths with `manage-references add-list`, but the retrospective still finds 19 realized-but-undeclared paths (git-core main and test files, git-core.adoc, git-forms CloneReader and DocumentTransitionsTest, several new git-store test helpers). `check-manifest-consistency` rule M6 (declared_vs_realized_set) reports fail; request-result alignment counts the drift above the 5-file bar.

## Root cause

A deviation answer that widens scope changes the plan's surface, but nothing re-derives the outline's declared files or records the new module as in scope, so the declaration and the realization diverge silently.

## Proposed action

When a deviation prompt is answered with a scope-widening option, phase-5-execute should append the newly in-scope paths to the owning deliverable's declared files (or record an explicit scope-amendment decision naming them), so later gates and the retrospective grade against the amended surface.

## Evidence

- aspect: manifest_decisions - declared_vs_realized_set fail, 0 outline_only, 19 references_only
- aspect: request_result_alignment - 19 undeclared files, mostly from the two operator decisions
- aspect: chat_history_analysis - deviation prompts task3-rejected-retry and task3-writer-collections answered "Change git-core" and "Add a parameter"
