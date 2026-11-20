envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:17Z

# Candidate lesson: outline left a specified refusal (anchor key equal to signing key) without a check

**Source:** Q-Gate finding 293efd (phase 3-outline, severity warning, resolution taken_into_account)
**Plan:** plan-07-store-module-registry-spi (PR #41, merged)
**File:** git-store/src/main/java/de/cuioss/git/document/store/StoreConfiguration.java

## Observation

`doc/specification/git-store.adoc` section Configuration defines `anchor.signed.key-source` /
`anchor.signed.key` as "the anchor key of signed; never the commit-signing key". Deliverable 4 typed and
validated every present key of the section but named no start check refusing an anchor key equal to the
signing key. The Q-Gate caught it; deliverable 4 then gained the refusal (MALFORMED naming `anchor.signed.key`,
never the value), with StoreConfigurationTest cases and a success criterion.

## Candidate rule

When a deliverable implements a specification section, walk every "never" / "must not" clause of that section
and map each to a check and a test case in the deliverable, not only the key typing and presence rules.

## Possible home

Project-level spec-to-deliverable guidance (specification documents carry negative constraints that
key-by-key validation misses).
