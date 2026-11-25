envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:19:12Z

## Candidate lesson: the Sonar quality gate fails on rules a local verify cannot see

Signal: automated review (Sonar quality gate on PR #64).

What happened: the first push failed the Sonar quality gate with 21 new-code findings that the local `verify` and `verify -Ppre-commit` builds did not show. A later round failed on rule java:S6539 (a class depends on too many other classes). That rule has no local check, so the first fix guessed at the count and missed. The gate passed only after a second attempt.

Suggested rule: before the first push of a Java change, read the open Sonar findings for the pull request through `workflow-integration-sonar read_open_issues` as soon as the analysis is available, and fix them in one round. For S6539, count a class's distinct imported and referenced types against the threshold (default 20) before splitting it, and do not guess.

Component hint: plan-marshall:workflow-integration-sonar (or the phase-6 automatic-review / Sonar step).
