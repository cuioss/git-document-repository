envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:58:07Z

# Candidate lesson: add a scripted fails-at-base check instead of a manual diff restore

- Source: plan-retrospective (llm-to-script-opportunities, chat-history); work log [ATTEMPT] "fails-at-base confirmed" lines for TASK-2, TASK-4, TASK-6 (and the same proof for TASK-8 and TASK-11)
- Suggested component: plan-marshall:execute-task
- Suggested category: improvement
- Confidence: medium

## Context

To prove that each new test fails without its fix, the executing agent saved the production diff to `.plan/temp/`, restored the task's production files from HEAD, ran the test class, and re-applied the diff, five times in this plan. It also extracted JGit sources into `.plan/temp/jgit-src/`. The scratch files (three patch files, a commit message, the extracted sources) were left behind.

## Root cause

execute-task asks for a fails-at-base proof but offers no deterministic tool for it, so the agent rewrites and restores the worktree by hand. That is repetitive, risky (a failed re-apply would lose the fix), and leaves scratch state.

## Proposed action

Add an execute-task helper verb that runs a given test selector against the base version of named production files in a scratch copy (or a temporary stash/worktree), reports which tests went red, and always restores the tree. The agent then only names the test and the files.

## Evidence

- work.log [ATTEMPT] lines: "fails-at-base confirmed (3 new tests red at base)", "(4 result-contract tests red at base)", "(4 deliverable-3 tests red at base)".
- Envelope 1 hand-back: "I saved the production diff to .plan/temp/, restored only this task's own file from HEAD, ran the test class, then re-applied the diff"; ".plan/temp/ has scratch files".
