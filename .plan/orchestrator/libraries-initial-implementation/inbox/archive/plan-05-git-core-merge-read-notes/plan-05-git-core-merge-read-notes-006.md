envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:29:26Z

# A loop-back fix task was created with an empty skills list and no verification commands

**Source signal:** automated review (the triage round 2 settlement task), seen in the task records

**Component:** plan-marshall:manage-tasks (fix-task creation from triage); plan-marshall:phase-6-finalize (wait-region unified triage)

## What happened

TASK-014 ("Fix: second review round on PR 37 ...", origin `fix`, profile `implementation`) was created by the round-2 triage after the commit guard refused the inline edits. Its stored record has:

- `skills[0]` (empty)
- `verification.commands[0]` (empty); only a criteria sentence: "verify -Ppre-commit is green and the second-round review fixes are committed"

The round-1 fix tasks TASK-012 and TASK-013, created by the same triage workflow, each carry six skills (persona-plan-marshall-agent, ref-code-quality, java-core, java-null-safety, javadoc, junit-core) and two verification commands (compile, verify -Ppre-commit). The task ran correctly only because its edits were already applied and verified in the worktree before the task existed. A fix task that has real work left to do and no skills would run in 5-execute without the Java standards loaded, and with no machine-checkable verification.

The record does not show which creation path left the list empty. The orchestrator pointed at the commit-add TOON draft path, but this plan's logs do not show it.

## Candidate rule

Fix-task creation should resolve skills and verification commands the same way on every path: give the task the same skills as its profile and domain, plus the canonical gate command. At the least, refuse or warn on `origin: fix` with `skills: []` or no verification commands, so a hand-drafted task cannot silently skip the domain standards.
