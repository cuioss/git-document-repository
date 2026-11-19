envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:34:07Z

# git-workflow commit with nothing staged reports FETCH_FAILURE instead of naming the empty index

**Source signal:** retrospective chat-history (envelope 2 hand-back) and LLM-to-script opportunities

**Component:** plan-marshall:workflow-integration-git (`git-workflow commit`)

**Category:** bug

## Context

In 5-execute envelope 2, the deliverable-5 commit first failed with "nothing staged", but the verb returned the error code `FETCH_FAILURE`. The leaf staged the six task files with `git add` by hand, and the retry succeeded (commit `a621adf`). This is separate from the finalize commit-guard refusal (`no_sanction`) that the lessons-capture step already reported.

## Root cause

The commit verb maps an empty-index outcome onto an unrelated error code. It also does not stage the task's declared changed paths itself, so every caller has to know to run `git add` first.

## Proposed action

Return a dedicated outcome such as `nothing_staged` that names the unstaged paths. Optionally accept the task's changed-path list and stage exactly those paths before committing, so the leaf does not have to stage files by hand.

## Evidence

- aspect: chat_history_analysis: envelope 2 hand-back says "the first commit attempt failed with nothing staged (the verb returned FETCH_FAILURE)"
- aspect: llm_to_script_opportunities: candidate "explicit git add of task files before git-workflow commit"
