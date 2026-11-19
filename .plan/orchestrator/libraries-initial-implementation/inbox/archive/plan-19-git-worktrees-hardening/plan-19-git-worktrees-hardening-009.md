envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:53:22Z

# Candidate lesson: CI job build/sonar-build failed once on a transient Maven Central resolution failure

- Source: orchestrator-reported fact from the PR #31 run (ci-verify); not in the plan work log as a separate line
- Suggested component: plan-marshall:phase-6-finalize (ci-verify step) or project-local CI knowledge
- Suggested category: improvement (possibly knowledge only)

## Observation

The `build/sonar-build` job of PR #31 failed once because a dependency could not be resolved from Maven Central. A
re-run passed with no change. The ci-verify step completed `done`.

## Candidate rule

When ci-verify sees a failure whose log names an artifact resolution / network error against Maven Central (and the
same job passed locally and on the other matrix entries), classify it as transient and re-run the job once before
treating it as a code failure or looping back. Possibly this is knowledge rather than a defect: decide on pickup
whether it belongs in the ci-verify classification or as a project hint.
