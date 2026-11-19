envelope_version=1
sender_type=plan
sender_id=plan-18-leftover-fixes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:23:38Z

# Candidate lesson: ordered nested lifecycle tests were not runnable one at a time

Signal source: review-bot finding remediated in-run (CodeRabbit, PR #28, finding ce144b, git-testing/src/test/java/de/cuioss/git/document/testing/PerClassLifecycleTest.java:69, also line 170). Fixed by TASK-11, commit 3ffe210.

## Finding

`PerClassLifecycleTest` split one lifecycle scenario across `@Order`-ed nested test methods that hand state forward. Running only `shouldCleanUpPreviousMethodFixture` left `previousMethodDirectory` null; running only a constructor test made the outer `@AfterAll` fail. Jupiter allows selecting single nested methods, and `@Order` does not pull in their predecessors.

## Candidate rule

Do not make a test depend on state left by an earlier ordered test. Have one test launch the full ordered scenario (for example through the JUnit Platform launcher, keeping real method-context boundaries) and assert on its results.
