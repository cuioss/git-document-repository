envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:16:12Z

# Persist per-file assessments in the deep outline lane so coverage checks run

Component: plan-marshall:phase-3-outline. Category: bug. Confidence: high. Source: plan-retrospective.

## Context

plan-23-forms-hardening ran the deep outline lane (10 deliverables, 32 mutation paths) and left the per-file assessment store empty (0 records). Both Q-Gate runs (3-outline and 4-plan) therefore reported `assessment-coverage` and `missing-coverage` under `not_run_validators` and wrote `deliverable-hashes --write --partial`, so no whole-outline aggregate row exists. The retrospective `outline-vs-shipped` aspect found 29 of 29 shipped paths `touched_but_unassessed`.

## Root cause

The deep lane produced a full outline without writing assessments to the store, and the Q-Gate validators degrade to "not run" instead of raising a finding when the store is empty on a deep-lane plan.

## Proposed action

Make the deep lane of phase-3-outline write one assessment per declared file before the Q-Gate, and have the Q-Gate treat an empty store on a deep-lane plan as a finding rather than a silent not-run.

## Evidence

- aspect: outline_vs_shipped - assessments_store_present false, touched_but_unassessed 29/29
- aspect: chat_history_analysis - 3-outline and 4-plan Q-Gate hand-backs list not_run_validators assessment-coverage and missing-coverage, hashes written --partial
