envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:02Z

# Candidate lesson: outline stated an unverified false premise about the git-core cache repository

Source: Q-Gate finding d8ec7f (phase 3-outline, false premise, severity warning), resolution taken_into_account.

## Observation

Deliverable 6 listed org.eclipse.jgit.internal.storage.dfs.DfsText for native-image metadata "when a git-core path reaches DFS storage (the in-memory cache repository is a DFS repository)". A content sweep (139 files, clean coverage) for InMemoryRepository|storage.dfs|DfsRepository found no hit in git-core/src/main; only git-testing uses DFS. The git-core cache repository is the local bare clone of ADR-0002, not a DFS repository. The project requires every statement about an external product to be checked against its source.

## Resolution in run

Carried into TASK-006 (drop DfsText unless a reaching code path is named and verified against JGit 7.8.0.202609011348-r), TASK-007 (test must not assert DfsText) and TASK-010 (docs must not call the cache repository DFS). Execute verified against the JGit 7.8.0 jar and sources: only JGitText is reached; DfsText not listed.

## Candidate rule

A claim in an outline about which JGit internals a module reaches is backed by a code search of that module's main sources and the named JGit version, not inferred from a repository's name.
