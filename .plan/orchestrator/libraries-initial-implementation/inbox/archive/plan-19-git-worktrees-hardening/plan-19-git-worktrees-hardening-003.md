envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:31Z

# Candidate lesson: JGit Config.getEnum throws on invalid values, so a best-effort step must catch IllegalArgumentException too

- Source: pr-comment finding f7c611 (CodeRabbit, PR #31, JGitWorktreeOperations.java:734), resolved fixed via TASK-11 (commit 9b4f3b8)
- Suggested component: git-worktrees (project-local; commit from a working tree, linked reflog step)
- Suggested category: bug

## Observation

`writeLinkedReflogs` is documented as best-effort: a reflog failure must not fail a commit that is already stored.
It caught only `IOException`. `appendLinkedReflogs` reads `core.logAllRefUpdates` through `Config.getEnum`, whose
default getter throws `IllegalArgumentException` for an invalid configured value. That unchecked exception escaped
`commit` after the commit object was stored, so the caller got an exception instead of `GitResult.ok`.

## Resolution in this run

The best-effort step also catches the configuration failure and logs only the commit id, so the outcome stays `OK`.

## Candidate rule

A step documented as best-effort after an irreversible write must catch every failure its reads can raise, including
unchecked configuration-parse exceptions from JGit `Config` getters, not only `IOException`. Add a test that sets a
malformed configuration value.
