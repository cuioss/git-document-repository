envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:20Z

# Candidate lesson: never build a GitResult detail from getSimpleName() in a catch block

- Source: PR #35 review comment by coderabbitai (finding ddb784, PRRC_kwDOVwLcIM8AAAABE9pQTA), resolution fixed in TASK-010 (commit 2c2c8e03)
- Component: git-core InCoreWriter.write, ReferenceUpdater (two IOException catches)

## Defect

The catches returned `GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName())`. For an anonymous IOException subclass getSimpleName() is the empty string, GitResult.of rejects a blank detail with IllegalArgumentException, and that exception escapes the method instead of FAILED being returned. In InCoreWriter this is reachable through a caller-supplied Signer that throws such an exception.

## Fix

All three catches use JGitRepositoryOperations.failure(e), which falls back to the binary class name when the simple name is empty and reports only the class name, never the message. TASK-010 added a test with an anonymous IOException.

## Candidate rule

In git-core, map a caught exception to a result only through JGitRepositoryOperations.failure(e); a hand-written getSimpleName() detail is a defect. A new class copying the catch pattern should be checked for this in review.
