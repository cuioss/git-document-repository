envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:58:02Z

# Candidate lesson: finalize loop-backs re-fired every step and pushed a multi_module feature past the 2.5M-token error anchor

- Source signal: plan-retrospective, plan-efficiency aspect ([BUDGET] error)
- Metrics: 3,871,880 dispatched tokens (floor: 1-init unmeasured, 6-finalize not yet closed) against the multi_module+feature error anchor of 2.5M. 5-execute 1.68M (6 closes), 6-finalize 1.41M. Worked time 71m (under the 120m anchor); wall 12h59m, mostly an overnight operator wait.
- Firing counts from status.metadata.phase_steps: finalize-step-simplify 7, pre-push-quality-gate 5, ci-verify 3, automatic-review 2, sonar-roundtrip 2. Loop-back budget `wait-region-unified-triage`: spent 4, one extra round granted by the operator.

## What happened

The original plan had 5 tasks. Finalize triage of CodeRabbit and Sonar findings added TASK-7 to TASK-10 over four unified-triage rounds (review fixes, the S5443 fix, coverage tests for the Sonar coverage gate, and a one-line S3415 fix). Each round looped back to 5-execute and then re-ran the finalize chain from the top. `finalize-step-simplify` ran 7 times and applied 3 edits in total; `pre-push-quality-gate` ran 5 times. Two of the rounds came from Sonar gates (new-code coverage, then an assertion-argument issue in the new coverage tests) that a coverage run before the first push would have shown.

## Candidate rule

Two separate levers: (1) a finalize loop-back whose fix task touched only test files, or one line, should not re-fire finalize-step-simplify over the whole footprint again; re-firing should be scoped to the files the fix task changed. (2) When the project's CI enforces a Sonar new-code coverage gate, the pre-push quality gate (or 5-execute exit) should run the coverage build once before the first push, so coverage and coverage-test findings arrive in one round instead of two.

Classification is deferred to the orchestrator.
