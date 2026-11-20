envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:14Z

# Candidate lesson: outline verification used a build goal outside the project's canonical list

**Source:** Q-Gate finding ba5c90 (phase 3-outline, severity warning, resolution taken_into_account)
**Plan:** plan-07-store-module-registry-spi (PR #41, merged)

## Observation

The first outline draft had deliverables 1-5 verify with `maven run --command-args "test"`. The project's
CLAUDE.md (Build Commands) names exactly four canonical builds: `compile`, `verify -Ppre-commit`, `verify` and
`verify -Pcoverage`, and says never to hard-code other invocations. The Q-Gate caught it, and the operator chose
`verify` for every deliverable.

## Candidate rule

When the outline writes per-deliverable verification commands, draw them only from the project's canonical
build command list (CLAUDE.md / architecture-resolved commands), not from a convenient goal like `test`.

## Possible home

The phase-3-outline deliverable verification guidance, or a project-level note for this repository.
