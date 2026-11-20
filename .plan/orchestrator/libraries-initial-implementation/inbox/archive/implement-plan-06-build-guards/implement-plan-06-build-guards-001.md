envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:16:48Z

# Candidate lesson: outline substituted a requested artifact without recording the deviation

Source: Q-Gate finding 382599 (phase 3-outline, request alignment, severity warning), resolution taken_into_account.

## Observation

The clarified request (and GDR-GEN-1 in doc/specification/general.adoc) scoped the consumer check as "a test consumer project depending on git-core alone whose resolved dependency set is asserted". The first outline realized it instead as an in-module `bannedDependencies` execution `consumer-isolation` in git-core/pom.xml (searchTransitive=true, compile/runtime allowlist) and never named that as a deviation from the request.

## Resolution in run

The owner confirmed the enforcer-rule substitution; the outline gained a "Deviation from the request" section stating the reasons (same compile/runtime closure a consumer receives, nothing extra published, no new plugin or dependency).

## Candidate rule

When an outline realizes a requested artifact by a different mechanism, it records the substitution and its reason explicitly (and confirms with the owner) rather than silently swapping it.
