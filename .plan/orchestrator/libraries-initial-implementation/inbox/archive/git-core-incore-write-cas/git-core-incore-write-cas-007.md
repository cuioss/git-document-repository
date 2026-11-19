envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:36Z

# Candidate lesson: Repository.updateRef(name) follows symbolic references; use updateRef(name, true) to act on the named ref

- Source: PR #35 review comment by coderabbitai (finding f520b9, PRRC_kwDOVwLcIM8AAAABE9pQYg, severity Major), resolution fixed in TASK-010 (commit 2c2c8e03)
- Component: git-core ReferenceUpdater.compareAndDelete (GDR-CORE-5)

## Defect

compareAndDelete documents that it deletes the reference named by refName, but repository.updateRef(refName) runs with detach=false, and JGit resolves a symbolic reference to its target in that mode. A call with a symbolic refName (refs/heads/alias -> refs/heads/main) could delete main and return OK while alias remained.

## Fix

compareAndDelete uses updateRef(refName, true); TASK-010 added the alias test (alias removed, main keeps its commit) and checked compareAndSwap for the same defect. JGitRepositoryOperations already used updateRef(name, true) for symbolic pinned references.

## Candidate rule

Every RefUpdate in git-core that is meant to act on the named reference itself (delete, compare-and-swap) must use the detaching overload updateRef(name, true), and its tests must include a symbolic-reference case.
