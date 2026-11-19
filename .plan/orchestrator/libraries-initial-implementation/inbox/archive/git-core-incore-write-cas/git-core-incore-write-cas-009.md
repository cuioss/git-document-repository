envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:53Z

# Candidate lesson: a "value absent from rendering" assertion needs a generated value from a disjoint character class

- Source: PR #35 review comment by coderabbitai (finding 86e418, PRRC_kwDOVwLcIM8AAAABE9pQgw), resolution fixed in TASK-011 (commit 2c2c8e03)
- Component: git-core RepositoryOperationsRecordsTest (line 116)

## Defect

The test generated the URL query with text(), which returns letters only, and asserted the query does not appear in the rendering. The rendering itself contains letters from fixed text (FetchCommitInput, cache, commit, url) and from generated values, so a short query such as "t" can appear even when it is removed correctly: the test was intermittently flaky.

## Fix

The query is prefixed with a digit ("0" + text()), a character class the rendering never contains.

## Candidate rule

When a test asserts that a generated value does NOT appear in some output, make the value come from a character class (or carry a marker) the output can never contain; a bare cui-test-generator letters() value collides with fixed text by chance.
