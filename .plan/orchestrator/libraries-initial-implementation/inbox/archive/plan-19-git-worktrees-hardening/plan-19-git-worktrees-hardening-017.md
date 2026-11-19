envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:58:16Z

# Candidate lesson: extend the test-identifier diff helper to Maven Surefire output

- Source: plan-retrospective (chat-history); envelope 2 hand-back, "Deviations to check"
- Suggested component: plan-marshall:execute-task
- Suggested category: improvement
- Confidence: medium

## Context

execute-task has a check that compares the test identifiers of a build before and after a test task, to confirm the new tests actually ran. On this Maven plan the agent skipped it: "the identifier-diff helper only reads pytest output, so it was not run on these Maven builds". New-test presence was inferred from the total test counts instead (522 -> 529 -> 534 -> 537).

## Root cause

The helper parses only pytest output. For Maven/JUnit projects the check silently degrades to a count comparison, which cannot tell a new test from a renamed or removed one.

## Proposed action

Teach the helper to read Surefire results (the `target/surefire-reports/TEST-*.xml` testcase names, or the build log), or have execute-task state explicitly that the check is pytest-only and record the skip as a coverage gap rather than leaving it to the agent.

## Evidence

- Envelope 2 hand-back: "Test-identifier check skipped: the identifier-diff helper only reads pytest output".
