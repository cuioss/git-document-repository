envelope_version=1
sender_type=plan
sender_id=plan-05-git-core-merge-read-notes
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T19:29:04Z

# Test fixtures depended on random content and on the developer's global git config

**Source signal:** automated review (coderabbit findings remediated in this run, over two rounds)

**Component:** git-core tests `InCoreMergerTest`, `RedirectEndToEndTest`, `RepositoryReaderTest` (project code)

## What happened

- 7abdfc: the conflict fixtures in `InCoreMergerTest` drew the content for both sides from a generator of 1 to 40 letters. Two draws can be equal, and then the merge is clean and the conflict assertions fail intermittently. Fixed with `sideFile(OURS)` / `sideFile(THEIRS)`, which put the side name in front so the two sides always differ.
- f7f9e4 (round 1), then af6d0c (round 2): `RedirectEndToEndTest` used whatever redirect settings it inherited. With `http.followRedirects=false` in the user's git config, and in the next round with `http.maxRedirects=0`, JGit rejects the first 302 before the policy guard under test runs. Round 1 pinned only `followRedirects`, so the reviewer had to come back for `maxRedirects`.
- 44009a (nitpick): the bounded-walk test in `RepositoryReaderTest` still passed after the read-ahead regression came back, because the grandparent existed. It now names a grandparent commit id that does not exist.

## Candidate rule

A test that checks a guard must hold every setting that could stop the run before the guard is reached. Pin the whole related config group (all `http.*` redirect keys), not just the one key a reviewer named. Conflict fixtures need content that is distinct by construction, never independent random draws. A regression test must fail on the regressed code. Make the fixture break the forbidden behaviour, such as a missing object for a read-ahead.
