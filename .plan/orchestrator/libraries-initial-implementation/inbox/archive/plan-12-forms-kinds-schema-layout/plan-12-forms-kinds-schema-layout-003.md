envelope_version=1
sender_type=plan
sender_id=plan-12-forms-kinds-schema-layout
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T21:35:18Z

component=plan-marshall:workflow-integration-git
category=bug
title=Derive the commit co-author trailer from the recorded model on every commit
confidence=high
source_plan=plan-12-forms-kinds-schema-layout

# Derive the commit co-author trailer from the recorded model on every commit

## Context

Plan-12 ran on Sonnet (`status.metadata.model: claude-sonnet-5-5`). Early leaf commits carried an Opus co-author
trailer; the trailer was fixed for later commits. The squash merge 8d09629 on `main` still ends with both lines:
`Co-Authored-By: Claude Sonnet 5.5` from the PR body and `Co-authored-by: Claude Opus 5.5` collected by GitHub from the
earlier branch commits. So the correction on the branch did not reach `main`: a squash merge keeps the co-authors of
every commit it folds.

## Root cause

The trailer is written by the leaf from prose instead of from the recorded model, so a leaf that copies the wrong
example writes the wrong model; and once such a commit is on the branch, squash merging preserves it.

## Proposed action

- Let the commit helper (`git-workflow commit` / `format-commit`) fill the co-author trailer from
  `status.metadata.model`, and refuse a caller-supplied trailer that names a different model.
- Before merge, check the trailers of every branch commit (not only the PR body) against the recorded model, and fix
  them by rewording before the squash if they differ.

## Evidence

- `git log -1 8d09629`: both the Sonnet and the Opus co-author lines are present
- status.metadata: model claude-sonnet-5-5, model_source self_reported
- aspect: llm_to_script_opportunities - trailer is deterministic from recorded metadata
