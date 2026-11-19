envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:20:45Z

# Candidate lesson: JGit URIish parses an uppercase scheme as a local path, leaking user information when rendered

- Source: PR #35 review comment by coderabbitai (finding 728098, PRRC_kwDOVwLcIM8AAAABE9pQfQ, severity Major, Security and Privacy), resolution fixed in TASK-011 (commit 2c2c8e03)
- Component: git-core RepositoryOperations.FetchCommitInput.toString / JGitRepositoryOperations.display

## Defect

FetchCommitInput.toString() rendered its URL through JGitRepositoryOperations.display(new URIish(url)). URIish matches its scheme pattern only in lowercase, so HTTPS://user:SECRET@host.example/repo.git falls through to the local-file pattern and the whole string, user information included, is stored as the path. display kept the path, so the rendering contained the credential; the catch never ran because parsing succeeded.

## Fix

TASK-011 made the rendering omit the user information for such URLs and added a test rendering HTTPS://user:SECRET@host.example/repo.git that asserts SECRET does not appear.

## Candidate rule

Redaction that relies on a parser must be tested with inputs the parser does not classify as expected (uppercase scheme, missing host); a successful parse does not mean the credential was recognised. A rendering whose parsed form has no host must not echo the raw input.
