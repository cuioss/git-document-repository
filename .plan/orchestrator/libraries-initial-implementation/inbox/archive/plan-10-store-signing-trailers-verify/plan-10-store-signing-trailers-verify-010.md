envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:36:02Z

# Candidate lesson: inline review fixes in 6-finalize were refused by git-workflow commit (no_sanction), twice

Source: script-failure cluster `plan-marshall:workflow-integration-git:git-workflow` (exit 1, error_code INVALID_INPUT, refused true, reason no_sanction, phase 6-finalize, step null), two occurrences:

- 2026-11-23T11:34:08Z, during the wait-region-unified-triage loop-back (Sonar S6878 in AllowedSigners). It was rerouted as fix task TASK-15 through 5-execute.
- 2026-11-23T13:15:48Z, again after unified triage, 2 changed paths under git-store/src/main. It was committed afterwards as 7304354 (loop-back iteration 5/5, target 6-finalize inline).

Component: plan-marshall:phase-6-finalize (wait-region unified triage, the inline-fix path) and plan-marshall:workflow-integration-git (commit sanction guard)

## What happened

The triage hook fixed a small finding inline in 6-finalize and called commit without `--step`, without an open loop-back fix task, and without a review-rubric section-6 inline-correction record. The guard refused correctly. The same mistake recurred about 1h40m later in the same plan, so the first refusal did not change how the triage path commits.

## Candidate rule

The wait-region-unified-triage inline-fix path must pass `--step` with the composed phase-6 manifest step id, or record the section-6 inline correction, before it calls commit. Otherwise it must open a fix task first. Check whether the triage workflow documents a sanctioned inline-commit form at all; if it does not, the workflow is missing a step.
