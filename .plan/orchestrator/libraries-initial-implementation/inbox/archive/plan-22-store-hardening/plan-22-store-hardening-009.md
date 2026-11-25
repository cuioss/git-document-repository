envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:59Z

Candidate lesson (PR #76 review, git-core: classification of a leased-push refusal)

What happened: Comment af2083 reported that a leased push refused by the native Git remote with a stale old value was classified as REJECTED instead of LEASE_REFUSED. TASK-13 fixed it in JGitRepositoryOperations. TASK-14 added RefusingReceiveProtocol to test that this case is LEASE_REFUSED, while a permanent refusal and a plain push stay REJECTED. The resulting rule (AGENTS.md) recognises a lost race only by two remote messages; every other wording is REJECTED.

Evidence: the pr-comment finding resolved as fixed, and the work-log titles of TASK-13/14.

Suggested rule: An outcome derived from a remote's free-text message must name the exact messages it recognises, and must have a test against a scripted receive side for each recognised message and for one unrecognised message.
