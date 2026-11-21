envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:54:06Z

# Candidate lesson: one CodeRabbit review body carried 11 minor/nitpick findings, all valid (fixed in run)

- Signal: review-bot finding remediated in run, hash 5e1483, PR #48, CodeRabbit review_body
- Fix: TASK-17, fix commit 9f3e622d
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The review body held 10 minor findings and 1 nitpick, beyond the inline comments:
- the spec did not distinguish fetch effects from conflict effects
- a DEBUG log attached the raw throwable on the sanitized failure path, in `DraftExpiry` and `DraftReferences` (GDR-NFR-3 covers debug output too)
- the JVM-wide `RefusingFS.systemReader` prerequisite was undocumented
- tests asserted paths but not file contents
- fixtures generated independently could collide, making change-detection tests vacuous
- the draft ref was published before the first draft commit was ready
- `Saved` was returned for a target branch that does not exist
- a GC skipped by a recent `gc.pid` was reported as successful expiry
- `LogMessages.adoc` hand-mirrors `GitStoreLogMessages`, now covered by a test
- author and committer names were not asserted exactly

## Possible rule (for the orchestrator to classify)

Several of these are generic. Never attach the throwable to a log record on a path promised as sanitized. A fixture value whose difference the test relies on must be distinct by construction. A test must assert content, not only presence. A hand-maintained catalogue must be checked against its source by a test.
