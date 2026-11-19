envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:33:30Z

# Outline design questions should check the project's own test libraries before recommending

**Source signal:** retrospective chat-history pivots (2 of 2 outline questions answered against the recommended option)

**Component:** plan-marshall:phase-3-outline (outline_prompt question composition)

**Category:** improvement

## Context

The outline asked the operator two design questions. Both recommended options were rejected:

- Redirect end-to-end test: the outline recommended a JDK `HttpsServer` plus a self-signed PKCS12 keystore committed under `git-core/src/test/resources/redirect/`. The operator answered "why not cuihttpmock", meaning `de.cuioss.test:cui-test-mockwebserver-junit5`. The parent BOM (`cui-java-bom` 1.7.7) already manages that library, and the loaded domain has a skill for it (`pm-dev-java-cui:cui-http-testing`).
- Merge conflict carrier: the outline recommended `CONFLICT` with the paths in the detail plus a public `conflictingPaths(...)`. The operator asked for "an object carrying the content", which became the sealed `MergeResult`.

Each rejection forced an outline revision and a success-criteria refreeze, which needed one more operator approval.

## Root cause

The outline built its options from first principles. It did not first look at the test libraries the project's BOM already manages or at the domain skills it had loaded. The option it ranked first ("no new dependency") weighed avoiding a dependency above using the project's own tooling.

## Proposed action

When an outline question is about test infrastructure, look in the resolved domain skills (for java-cui: `cui-http-testing`, `cui-testing`) and in the BOM-managed test artifacts first. Include the matching project library as an option, and recommend it when it fits. For result-shape questions, list a dedicated result type as an option whenever the existing result type cannot carry a value on a non-success.

## Evidence

- aspect: chat_history_analysis: two pivots at the outline questions; the operator rejected both recommended options
- aspect: request_result_alignment: D3 and D5(d) shipped as the operator redesigned them, not as first outlined
