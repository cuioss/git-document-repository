envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:28Z

# Candidate lesson: JGit RevWalk.parseCommit peels annotated tags; use parseAny to require a commit

- Source: PR #35 review comment by coderabbitai (finding e896a2, PRRC_kwDOVwLcIM8AAAABE9pQWw), resolution fixed in TASK-010 (commit 2c2c8e03)
- Component: git-core InCoreWriter (parent lookup, GDR-CORE-4)

## Defect

If a parent id names an annotated tag T pointing at commit C, JGit (7.8.0) RevWalk.parseCommit peels T to C, so the writer returned OK with C as the parent. The contract requires NOT_FOUND without writing for a non-commit parent id.

## Fix

The parent lookup uses parseAny and requires a RevCommit before insertion; direct commit ids stay accepted. TASK-010 added a test with an annotated tag id.

## Candidate rule

Where an API contract says "the id must name a commit", never resolve it with RevWalk.parseCommit, which silently peels tags; use parseAny and check the type. Verify such JGit behaviour against the JGit source of the version in use, as CLAUDE.md requires.
