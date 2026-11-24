envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:31:24Z

# Candidate lesson: Q-Gate 3-outline - SPI implementer missed from Affected files (under-coverage)

Source: Q-Gate finding 5d7174 (phase 3-outline, scope_criterion_validator, resolution taken_into_account).

Observation: deliverable 1 could change the signature of `AnchorProvider.obtain`. The outline did not list the test implementer `git-store/src/test/java/de/cuioss/git/document/store/TestAnchorProvider.java` (registered through `META-INF/services`) under any deliverable's Affected files. The content sweep found it. In the end the implementation changed the file to follow the new `obtain` signature.

Possible lesson: when an outline may change a ServiceLoader SPI signature in this repository, sweep for every implementer, including test implementers registered through `META-INF/services`. List them as Affected files, conditional on the signature change.
