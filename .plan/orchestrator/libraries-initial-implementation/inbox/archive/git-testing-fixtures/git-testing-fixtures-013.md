envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:57:31Z

# Candidate lesson: phase-3-outline froze the success criteria before the operator answered the outline questions

- Source signal: plan-retrospective, chat-history aspect (two `refreeze_requires_decision` returns from phase-3-outline, 2026-11-17)
- Resolution in run: operator approved a re-freeze twice; the second snapshot recorded 21 criteria

## What happened

The first phase-3-outline run wrote the outline, returned three `outline_prompt` questions with recommended answers filled in, and froze `work/criteria-snapshot.json` in the same run. The operator chose non-recommended answers for two of them (qualifier annotations for the fixtures, aligning the "with history" wording). Applying them changed the criteria, and `snapshot-criteria` refused to re-freeze without `criteria-refreeze-authorize`, so the leaf returned `status: error` and needed an operator prompt. The Q-Gate finding 9fe2d8 then added two more documents and the same re-freeze prompt came back a second time. 3-outline took three dispatches and two extra operator prompts that carried no new decision.

## Candidate rule

When phase-3-outline returns open `outline_prompt` questions, it should defer the criteria freeze until those answers are applied, or treat an answer to its own outline questions as authorizing the re-freeze. The re-freeze gate is meant to catch criteria drift after approval, and an outline that is still waiting for its own questions has not been approved yet.

Classification is deferred to the orchestrator.
