envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:46Z

# Candidate lesson: a test must not compare snapshots of a shared temporary root

- Source signal: automatic-review, remediated review-bot finding 8684a8 (coderabbitai, PR #23, InMemoryRepositoryExtensionTest.java:102)
- Resolution in run: fixed by TASK-7 part C, commit dfe96ce

## What happened

`shouldCreateNoDirectory` compared listings of `git-testing-*` directories in the system temp root, taken before and after the test. The file-backed fixtures use the same root and prefix. Another Maven test process (parallel forks, or another build) could create or delete such a directory between `@BeforeEach` and the assertion. The test would then fail even though `@InMemory` creates no directory.

## Candidate rule

To prove that a fixture creates no file, give the test JVM a private temporary root, or observe what that fixture itself creates. Never diff a directory shared with other processes, because that makes the test flaky under parallel or concurrent builds.

Classification is deferred to the orchestrator.
