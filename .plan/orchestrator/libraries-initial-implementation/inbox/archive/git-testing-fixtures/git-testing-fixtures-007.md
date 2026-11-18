envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:52:39Z

# Candidate lesson: JGit read-only pack and idx files break Files.deleteIfExists on Windows

- Source signal: automatic-review, remediated review-bot finding 7c40fb (coderabbitai, PR #23, TemporaryDirectories.java:65)
- Resolution in run: fixed by TASK-7 part B, commit dfe96ce. The operator first required the JGit 7.8 `FileUtils.delete` claim to be checked, and only then applied it.

## What happened

JGit marks the `.pack` and `.idx` files it keeps as read-only (`ObjectDirectoryPackParser.parse()` calls `setReadOnly()`). On Windows, `Files.deleteIfExists` throws `AccessDeniedException` for a read-only file. `visitFile` did not handle that, so the walk stopped and files were left on disk. A push to a `file:` remote that JGit keeps as a pack can reach this path.

## Candidate rule

Delete files in JGit fixture directories with `org.eclipse.jgit.util.FileUtils.delete(file, FileUtils.SKIP_MISSING)`. On failure it clears the read-only flag and retries, and other failures still propagate. Per CLAUDE.md, check the claim against the JGit source of the version in use before relying on it. That check was done here for JGit 7.8.

Classification is deferred to the orchestrator.
