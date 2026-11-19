envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:08Z

# Candidate lesson: hard-coded test include list drifted from the test sources

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 4cf458, git-testing/pom.xml:66). Fixed in commit 78c454f.

## Finding

The second Surefire execution (auto-close off) selected its tests through a hard-coded list of class names. The list omitted `PerClassLifecycleTest`, so the class-boundary cleanup checks never ran with auto-close disabled.

## Candidate rule

Do not select tests for an extra execution by a list of class names that must mirror the test sources. Use normal suite discovery or a JUnit tag declared on the tests, so the selection is derived from the sources. The fix removed the include list.
