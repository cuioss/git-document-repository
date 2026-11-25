envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:27:49Z

component=plan-marshall:workflow-integration-sonar
category=bug
signal=retrospective-audit
shape=actionable

# Sonar fetch_findings files a new pending finding for an already-pending issue key on every round

## What happened

In plan-17-timestamp-rfc3161 the one open Sonar issue java:S2143 (key AaIN1JmnF1H5L5GKrffy) was filed again on every sonar-roundtrip pass: 481785, 55fe52, edd11e, 8ed84b and 4481d4 — five pending sonar-issue findings for one Sonar issue by the end. `issues_fetched` (defined as the pending-finding count) read 3, 7 and 5 while the distinct open-issue count was 1-4. Every triage round had to dedupe by key by hand, and the store has no duplicate-link disposition, so the duplicates could only stay pending and kept `pending_findings_blocking_count` above zero.

## Corrective action

- In `fetch_findings`, when an incoming issue's `issue_key` already has a pending finding, update that finding in place (or skip it) instead of storing a new row.
- Publish a by-key distinct count beside `issues_fetched` so the gate and triage read the real number of open issues.
- Optionally add a `duplicate_of` resolution to manage-findings so a duplicate can be closed without counting as a dismissal.

## Impact

Inflated blocking counts across four finalize rounds, repeated LLM reconciliation in triage, and an ambiguous `issues_fetched` fact in the step record.
