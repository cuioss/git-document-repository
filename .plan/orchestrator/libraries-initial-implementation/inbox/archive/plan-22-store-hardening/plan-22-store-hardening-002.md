envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:46Z

Candidate lesson (Q-Gate 3-outline, finding 7aea10, scope_criterion_validator, under_coverage, D4)

What happened: The D4 criterion was "remote-controlled blobs are read bounded". A search for Integer.MAX_VALUE in git-store returned 5 files (AnchorNotes, Checkpoints, StoreIndex, DocumentRelease, ExplicitSave). D4 (i) covered only AnchorNotes and Checkpoints, but StoreIndex.contribute() reads blobs of branches fetched from the remote.

Evidence: qgate finding 7aea10. Resolved as taken_into_account: TASK-7 applied the single 16 MiB bound ExplicitSave.BLOB_LIMIT to every blob read, AnchorVerification included, and TASK-8 tested it. CodeRabbit later found further bound gaps on PR #76 (see the bounds candidate).

Suggested rule: Operationalise a bound criterion as a search for the unbounded call, and put every hit in scope or record it as an explicit limit. Do not list only the sites that motivated the plan.
