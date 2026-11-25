envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:20Z

component=plan-marshall:workflow-integration-sonar
category=bug
signal=qgate/sonar
shape=actionable

# Sonar false-positive dismissal refused with HTTP 400 and no reason, leaving the issue open

## What happened

In plan-17-timestamp-rfc3161 (PR #74), the dismissal of java:S2143 (use of `java.util.Date`, which the JDK API forces: `X509Certificate.checkValidity(Date)` and `PKIXParameters.setDate(Date)`) through `post_responses` (`transition falsepositive`) was refused by Sonar with HTTP 400 twice. The response gave no reason, so the issue stayed open and the merge needed an explicit operator waiver.

## Corrective action

- `post_responses` should surface the Sonar error body (the `errors[].msg` field) instead of a bare HTTP 400, so the cause can be acted on. Likely causes are missing "Administer Issues" permission for the token, or a pull-request issue whose transition is not allowed.
- On a refused transition, return a structured `dismissal_refused` outcome that names the issue key and the reason, and route straight to the operator-waiver path instead of retrying the same call.
- For JDK-API-forced `java.util.Date` usage, prefer a code-level fix where possible (keep `Date` at the API boundary only, converting from `Instant` there) so no dismissal is needed.

## Impact

Two wasted round trips and a merge that depended on a manual waiver.
