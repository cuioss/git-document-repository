envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:50Z

Candidate lesson (PR #76 review, recurring defect class: bounds not enforced end to end)

What happened: CodeRabbit found new bound gaps in several rounds, after the plan had already introduced ExplicitSave.BLOB_LIMIT:
- A provider proof had no size bound. A proof above about 12 MiB produces a Base64 note larger than BLOB_LIMIT, which the store's own readers (current(), kept()) then reject on every later read (comment 552401, fixed in TASK-9).
- Further bounded-read gaps were fixed in TASK-10 and tested in TASK-11 (ExplicitSaveBlobBoundTest, SignatureVerificationOversizedTest).
- The drafts.expiry configuration value was unbounded (fixed in TASK-17, tested in TASK-18).

Evidence: pr-comment findings resolved as fixed, and the fix-task titles TASK-9, 10, 11, 17 and 18 in the work log.

Suggested rule: A bound must hold on the write side as well as the read side. Every value the store writes (notes, kept blobs) must fit the limit its own readers apply. Every configuration value that drives a size or a time needs an explicit upper bound. One test per bound must exercise both directions.
