envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:52Z

# Candidate lesson: an @AfterAll "at least one fixture was created" assertion fails single-test runs

- Source signal: automatic-review, remediated review-bot finding 869e36 (coderabbitai, PR #23, LocalRepositoryExtensionTest.java:54)
- Resolution in run: fixed, commit f5c11ce

## What happened

An `@AfterAll` method asserted that `RECEIVED_DIRECTORIES` was not empty, and then checked that each recorded directory had been deleted. Running only a validation test (`shouldNotSupportUnannotatedParameter` or `shouldRejectAnnotatedParameterOfAnotherType`) creates no fixture. The selected test passed, but the `@AfterAll` check then failed the run.

## Candidate rule

Class-level teardown assertions must hold for any subset of the class's tests, including a single selected test. Assert over the population that was actually recorded (every recorded directory is deleted). Do not also assert that the population is non-empty.

Classification is deferred to the orchestrator.
