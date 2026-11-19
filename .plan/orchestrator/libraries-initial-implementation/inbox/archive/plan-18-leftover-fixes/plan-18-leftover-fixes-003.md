envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:01Z

# Candidate lesson: second Surefire execution overwrote the first execution's reports

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding 04920c, git-testing/pom.xml:60). Fixed in commit 78c454f.

## Finding

`git-testing/pom.xml` added a second Surefire execution (auto-close off) that runs classes shared with the default execution. Both wrote the same report filenames, so the second run replaced the default run's reports under `target/surefire-reports`.

## Candidate rule

When adding an extra Surefire (or Failsafe) execution that re-runs the same test classes, set a distinct `reportNameSuffix` (here `store-autoclose-off`) so both result sets survive.
