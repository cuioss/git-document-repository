envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:13:35Z

Candidate lesson (retrospective: deep-lane outline wrote no file assessments)

component=plan-marshall:phase-3-outline
category=bug
title=Write file assessments on the deep lane so Q-Gate coverage checks can run

## Context

The plan ran the deep planning lane with 4 deliverables and 71 declared modification files, yet its assessments store is absent (`assessments_store_present: false`). Both the 3-outline and the 4-plan Q-Gate reported `not_run_validators: assessment-coverage, missing-coverage` and wrote only partial deliverable hashes, so no whole-outline pass was ever recorded. The outline-vs-shipped aspect can only report 90 of 90 realized paths as "touched but unassessed".

## Root cause

The deep-lane outline path did not persist per-file assessments, and the Q-Gate treats their absence as "validator not applicable" rather than as a missing input.

## Proposed action

Make the deep lane record one assessment per declared file (certain include / exclude with reason) before the outline Q-Gate, and have the Q-Gate raise a finding when a deep-lane plan has an empty assessments store instead of silently skipping two validators.

## Evidence

- aspect: outline_vs_shipped - assessments_store_present false, assessments_read 0, touched_but_unassessed 90/90
- 3-outline Q-Gate hand-back: not_run_validators assessment-coverage, missing-coverage; hash write --partial
- 4-plan Q-Gate hand-back: same two validators not run, aggregate_written false
