envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:12:18Z

# Candidate lesson: the no-process guarantee silently depended on the git-core SystemReader startup prerequisite

- Source: review-bot finding 8c20f6 (coderabbitai, PR #30, Major, Security & Privacy), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/JGitWorktreeOperations.java:427 (also 440)

## Defect

In a fresh JVM with Git installed and JGit's default SystemReader, the first repository open can start a git process
to discover system configuration. A per-repository RefusingFS does not affect that process-wide reader. The git-core
specification documents this exception, but the worktree API claimed the no-process guarantee unconditionally.

## Fix in run

The guarantee is stated as conditional on the git-core startup exception in the Javadoc and the specification; the
reader is not replaced inside an operation.

## Possible rule

A downstream module that relies on a guarantee with a documented upstream exception (here git-core's SystemReader
note) must carry that exception into its own contract; check each claimed guarantee against the file that would
have to implement it.
