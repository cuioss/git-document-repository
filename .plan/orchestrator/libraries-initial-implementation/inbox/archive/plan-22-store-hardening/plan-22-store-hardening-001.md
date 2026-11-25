envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:40Z

Candidate lesson (Q-Gate 3-outline, finding dca6ed, scope_criterion_validator, under_coverage, D3)

What happened: The D3 success criterion "no listener, contributor or step failure covered by the Q2 rule escapes" named the anchor provider as an observer site, but the deliverable's affected files listed only five isolation sites. A content search for the isolation catch marker also hit AnchorEngine.java and AnchorVerification.java, which no deliverable covered.

Evidence: qgate finding dca6ed. Resolved as taken_into_account: both files were added to TASK-5/6, and the rule got a single home in StoreEvents.rethrowIfFatal.

Suggested rule: When a criterion states a rule that covers every site ("no X escapes"), derive the affected-file list from a content search for the rule's marker, not from memory. Record any excluded site together with its reason.
