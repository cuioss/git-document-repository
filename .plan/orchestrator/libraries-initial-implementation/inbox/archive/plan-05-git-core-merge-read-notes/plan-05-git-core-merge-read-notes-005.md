envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:29:13Z

# Sonar flagged the same test-code smells across every new test class

**Source signal:** automated review (22 sonar-issue findings remediated in this run)

**Component:** git-core tests and main code (project code); pm-dev-java:junit-core review rules

## What happened

The first sonar round on PR #37 reported 20 issues, all fixed inline:

- java:S5778 (13 issues, MAJOR): an `assertThrows` lambda with more than one call that could throw. Found in `RepositoryReaderTest`, `MergeResultTest` and `GitNotesTest`. The fix moves the setup out of the lambda.
- java:S1192 (4 issues, CRITICAL): a literal repeated three times (`"repository"`, `"refName"`, `": not found"`) in `RepositoryReader`, `ReferenceUpdater` and `GitNotes`.
- java:S1130 (3 issues): a `throws` clause the method body can never trigger.

The second round added java:S4144 (2 issues): the in-memory and local copies of the symbolic-reference tests had identical bodies. They are now one test that runs a shared helper on both databases.

None of these were caught by `verify -Ppre-commit` or by the finalize simplify step before the push.

## Candidate rule

In new JUnit code, keep exactly one call that can throw inside each `assertThrows` lambda. Declare no `throws` the body cannot reach. Name a constant for any literal used three or more times in a class. When the same test must run on two backends, share one helper rather than copying the test body. A pre-push check, either a Sonar-equivalent rule in pre-commit or a review item, would have saved one sonar round.
