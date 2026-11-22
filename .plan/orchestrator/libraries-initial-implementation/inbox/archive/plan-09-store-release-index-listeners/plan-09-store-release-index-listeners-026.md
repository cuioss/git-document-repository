envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:58:00Z

component=plan-marshall:phase-3-outline
category=improvement

# Record component assessments in the deep lane so the coverage checks can run

Source: plan-retrospective of plan-09-store-release-index-listeners (outline-vs-shipped aspect, Q-Gate returns).

## Context

The deep-lane outline recorded no component assessments. Both Q-Gate passes (outline and plan) reported `assessment-coverage` and `missing-coverage` as not run, so they wrote only partial hashes and no full-pass row. The outline-vs-shipped aspect found all 37 shipped paths unassessed, including the 7 paths added by fix tasks. The outline still listed the 30 files correctly, but nothing assessed them.

## Root cause

The deep-lane outline built its file list without writing assessments to the assessment store, and nothing fails when that store stays empty.

## Proposed action

Have the deep-lane outline write one assessment per declared file (include or exclude, with a certainty). Or make Q-Gate report an empty store in the deep lane as a finding, not a silent "not run".

## Evidence

- aspect: outline_vs_shipped - assessments_store_present false, touched_but_unassessed 37 of 37
- aspect: chat_history_analysis - Q-Gate 3-outline and 4-plan: not_run_validators [assessment-coverage, missing-coverage]
