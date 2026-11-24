envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:39Z

# Candidate lesson: Q-Gate 3-outline - deliverable verification used a non-canonical build command

Source: Q-Gate finding f5596f (phase 3-outline, resolution accepted).

Observation: deliverables 1, 2 and 3 named `maven run --command-args 'test -pl git-store -am'` as their verification command. The project's CLAUDE.md allows only the canonical commands (compile, verify -Ppre-commit, verify, verify -Pcoverage). The finding was accepted without a change, because the PR was gated by `build / conclusion` and the finalize verification.

Possible lesson: in this repository, the outline's per-deliverable verification should use one of the canonical CLAUDE.md commands. If a module-scoped test run is wanted for speed, the outline should state why it is an acceptable variant. Otherwise the Q-Gate flags it on every plan.
