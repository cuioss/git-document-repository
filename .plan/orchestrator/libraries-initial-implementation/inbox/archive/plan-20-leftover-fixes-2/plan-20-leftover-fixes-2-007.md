envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:09Z

# Candidate lesson: CodeRabbit quota refusals caused close-and-reopen PR churn (#67 -> #68 -> #69)

Source: the plan-marshall:automatic-review step and the review-bot findings remediated in the run (signal_automated_review_count = 1).

Evidence (decision log, 2026-11-23):
- 20:11:10 refusal recovery ARMED on PR 67: claimed the coderabbit rate window, 2700 s.
- 21:02:01 RE-DELIVERED: PR 67 closed and reopened as PR 68. The log line itself says "This re-delivers a dropped request and buys back no quota - the limit is account-scoped".
- 21:03:23 PR 68 drew a fresh refusal; a new window of 3360 s was claimed.
- 22:05:07 RE-DELIVERED again: PR 68 closed and reopened as PR 69.
- 22:17:26 automatic-review recorded at 650693 tokens, 132 tool uses, 1626019 ms (about 27 minutes of step time, plus about 1.5 hours of window waits between 20:11 and 22:00).
- 23:02:35 and 06:38:37 later passes: coderabbit refused on quota again, but a finished review was on record (review_on_record_done), so the step was accepted without a new trigger.

Outcome: 14 CodeRabbit and 5 Sonar findings were resolved in the run (fix tasks 14-17, commits 62c20fe, 91337ae, 6d0310c, 89579fe; inline fixes b8e378c). One CodeRabbit Major (ae4d93, allow HEAD in ReferenceUpdater) was declined by the reconciliation guard because it contradicted standing decision D3(d).

Observed costs: two extra PR numbers (the PR history of one change is spread over #67, #68, #69); each close-and-reopen hit the same account-scoped quota again; the wait dominated finalize wall time.

Candidate rule (for the orchestrator to classify): on a free-tier CodeRabbit account, a quota refusal is not cured by close-and-reopen; prefer waiting for the window on the same PR, or accept a finished review on record, before churning PR numbers. Possibly a framework candidate for plan-marshall:automatic-review refusal recovery rather than a project lesson.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
