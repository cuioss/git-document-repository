envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:41Z

component=plan-marshall:phase-6-finalize
category=bug
signal=script-failure
shape=actionable

# Wrong PR number recorded for the plan (73 instead of 74)

## What happened

In plan-17-timestamp-rfc3161, the first PR number recorded for the plan was 73, but the real PR is #74. It had to be corrected afterwards with `create-pr --no-completion-log`. Every later step that reads the recorded PR number (CI waits, review fetch, Sonar, merge) would have acted on the wrong pull request.

## Corrective action

- Record the PR number only from the create call's own response (or from a lookup by head branch), never from a guess or a "latest PR" query.
- After recording it, check that the PR's head branch equals the plan's branch, and refuse to record a mismatch.

## Impact

A manual correction, and a risk of CI and review steps acting on an unrelated pull request.
