envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:22:45Z

# Candidate lesson: 6-finalize source commit refused without a sanction

Signal source: script-failure cluster (`[ERROR] ... script_failure`), notation `plan-marshall:workflow-integration-git:git-workflow`. Plan: plan-18-leftover-fixes (PR #28, merged as 7418f76).

## What happened

In 6-finalize, a `git-workflow commit` of source edits was refused because no sanction was present. The commit gate requires one of: an open fix task, a review-rubric record, or an explicit `--step`. The triage step's own inline edits (made while remediating review-bot findings) carried none of these, so the commit failed until a sanction was supplied.

## Candidate rule

Before a finalize-phase triage edits source inline, establish the sanction the commit gate will ask for (open a fix task or record the review-rubric entry, or pass `--step`), instead of editing first and hitting the refused commit.

## Notes for classification

- Possibly a workflow-documentation gap in the finalize triage workflow, which does not state the sanction requirement where inline edits are allowed.
- Not a project-code defect.
