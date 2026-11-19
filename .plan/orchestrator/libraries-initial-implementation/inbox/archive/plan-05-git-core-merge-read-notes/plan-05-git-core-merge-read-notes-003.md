envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:28:53Z

# JGit 7.8.0 behaviour that broke the documented contracts, caught only at PR review

**Source signal:** automated review (coderabbit round 1, findings remediated in this run)

**Component:** git-core `RepositoryReader`, `InCoreWriter`, `MergeResult` (project code)

## What happened

The round-1 coderabbit review (head 4733bb1) found four places where the code or its Javadoc made a promise that JGit 7.8.0 does not keep. Each was confirmed against the JGit source and fixed:

- 2f7d83 (Major): `RepositoryReader.history` used `AndTreeFilter(PathFilter, ANY_DIFF)`. `TreeRevFilter.includeByTreeWalk` drops a merge that equals one parent at the path and rewrites its parents, so a merge that differs from the other parent was missing from the history. `setRewriteParents(false)` does not prevent this. Fixed in TASK-12: walk the unsimplified ancestry and compare each commit with its parents explicitly.
- eb07f3: a for-each over `RevWalk` uses `RevWalk.iterator()`, which fetches the next commit before the limit check runs, so `maxCount` 1 kept scanning. Fixed by driving `walk.next()` directly.
- 6eb0bc: the iterator wraps an IOException in the unchecked `RevWalkException`, so `history` threw instead of returning FAILED. The same `walk.next()` change fixed it.
- 2c0b55: the `InCoreWriter.writeTree` Javadoc promised a rollback. `ObjectDirectoryInserter` writes loose objects at insert time, so a failing source can leave unreferenced objects. The Javadoc was corrected.
- 0fc320: `MergeResult.Conflicting` rejected blank paths. `ObjectChecker` rejects only empty names on non-Windows platforms, so a merge conflict on a whitespace-only filename threw. Now only empty or absolute paths are rejected.

## Candidate rule

CLAUDE.md already requires checking JGit claims against its source. The gap is in what gets checked: the outline and execute phases checked the APIs the design names, but not the iteration and filter helpers it relies on (`RevWalk.iterator`, `TreeRevFilter`, inserter flush semantics). For every JGit helper whose behaviour a Javadoc contract depends on, check that helper's source too and pin the edge case in a test: merge commits, the limit boundary, I/O failure, filename edge cases.
