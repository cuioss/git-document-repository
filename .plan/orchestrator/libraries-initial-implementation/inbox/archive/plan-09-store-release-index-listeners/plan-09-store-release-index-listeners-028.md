envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:58:03Z

component=git-store
category=improvement

# Follow up the git-store duplicates and the JOL measurement scope that PLAN-09 left

Source: plan-retrospective of plan-09-store-release-index-listeners (chat-history aspect: simplify and envelope 2 hand-backs).

## Context

Two follow-ups were recorded only in leaf hand-backs and are kept nowhere else:
1. Duplicates. Three byte-identical private copies of `name(StoreResult.Outcome)` (DocumentRelease, StoreWriter, ExplicitSave). New private copies of `HEADS` ("refs/heads/") and `ORIGIN` ("origin") in ListenerDispatch, StoreIndex, StoreWriter and DocumentRelease, beside the existing `StoreReferences.HEADS` and `ExplicitSave.ORIGIN`. Simplify left them because removing them needs edits outside its line-level scope. Some may have moved during the StoreClone/CloneOpening split.
2. Measurement scope. `IndexFootprintTest` measures `GraphLayout.parseInstance(index)`, which also takes in the repository the index reads, so it overstates the index's own retained size against the GDR-NFR-1 bound of 16 MB.

## Root cause

Simplify works on the plan's added lines only, and the measurement choice was accepted as conservative without a spec note.

## Proposed action

A small git-store tidy-up plan: one shared home for the outcome name (for example on `StoreResult`), package-private shared ref constants, and either exclude the repository from the JOL walk or state in GDR-NFR-1 that the figure is an upper bound.

## Evidence

- aspect: chat_history_analysis - simplify findings "near-identical helper" and "duplicated constants ... recorded for follow-up"; envelope 2: "it overstates the index's own size"
