envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:10:51Z

# Candidate lesson: a declared departure from a plain-copy request was never put to the owner in the outline

- Source: Q-Gate finding f95cde (phase 3-outline, request_alignment), resolved taken_into_account
- Plan: plan-03-git-worktrees-import (PR #30)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/LinkedWorktrees.java

## What happened

The request asked for a plain copy of the pm-provider-git code. Deliverable 1's concurrency note changed
LinkedWorktrees.uniqueAdminDir to claim the directory atomically with Files.createDirectory (retry on
FileAlreadyExistsException) and said "see the open question below", but solution_outline.md had no
open-question section. The departure was declared but not put to the owner.

## Resolution in run

Owner decision obtained via AskUserQuestion (2026-11-18): keep the atomic claim, named in commit and PR.

## Possible rule

When an outline for a take-over / plain-copy request departs from the source code, the outline must
carry an explicit open question (or the recorded owner decision) for each departure; a forward
reference to a section that does not exist should be caught at outline authoring.
