envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:18Z

# Candidate lesson: seed-dependent test failure from a generated value colliding with captured log text

Source: Q-Gate finding b880d0 (phase 5-execute, severity error), resolution fixed by TASK-11 (commit ba870ed).

## Observation

The final `verify -Ppre-commit` sweep failed in git-core JGitRepositoryOperationsTest.refusedRedirectLogged (line 1496): `assertFalse(records.contains(credential.username()))` failed for seed -3633849498368272603. The generated username occurred by chance inside the captured log text. The identical source had passed the same gate three minutes earlier (688 tests); the branch had not touched the test or git-core main code.

## Resolution in run

TASK-11 changed the test to use a prefixed generated username so that a negative "value absent from logs" assertion cannot be satisfied or broken by chance substrings. Re-run with the failing seed and the full sweep passed.

## Candidate rule

A negative assertion that a generated value does not appear in free text (logs, messages) uses a value made distinctive (fixed prefix or marker) so a short random value cannot collide with unrelated text. Applies to cui-test-generator values in all modules.
