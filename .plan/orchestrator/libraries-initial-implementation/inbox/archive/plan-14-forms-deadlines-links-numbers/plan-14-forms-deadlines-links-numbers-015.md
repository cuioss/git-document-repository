envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:19Z

# Candidate lesson: source-tree commit in 6-finalize refused for missing sanction

- Source signal: script failure cluster, notation plan-marshall:workflow-integration-git:git-workflow (script_internal_failure, exit 1, refused: no_sanction), phase 6-finalize, 2026-11-27T19:01:01Z
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-6-finalize (wait-region-unified-triage inline replay) / plan-marshall:workflow-integration-git

## Observation

In loop-back iteration 3/3 (inline replay in 6-finalize), three Sonar findings were fixed inline and the commit was first
attempted without `--step`, so `git-workflow` refused it: no composed phase-6 manifest step id, no open loop-back fix task and
no section-6 inline-correction record. The retry under `--step sonar-roundtrip` succeeded (66f30e9).

## Possible rule

An inline fix committed in 6-finalize must name its sanction on the first attempt: pass `--step <composed phase-6 step id>`
(here the step that produced the findings, `sonar-roundtrip`). The triage inline-replay workflow should state this in the
commit instruction itself.
