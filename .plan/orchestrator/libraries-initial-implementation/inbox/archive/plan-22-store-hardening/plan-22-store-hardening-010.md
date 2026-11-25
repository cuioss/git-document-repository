envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:00Z

Candidate lesson (process: PR #76 needed 7 CodeRabbit rounds)

What happened: After the first push, PR #76 went through 7 automatic-review iterations (the work log reaches "iteration 7 at head c52acb4"). Six loop-backs from 6-finalize to 5-execute produced TASK-9 to TASK-24. Nearly every round found new, real defects of the same few cross-cutting classes: the isolation rule, lock holding and re-entry from callbacks, and bounds. Review-bot rate windows (one wait spanned a session restart) and Sonar suppressions (TASK-12) added more rounds.

Evidence: the work-log iterations of automatic-review, the "Phase: 6-finalize -> 5-execute" transitions, the fix tasks TASK-9 to TASK-24, and 41 pr-comment findings resolved as fixed.

Suggested rule: When a plan hardens a cross-cutting rule (isolation, lock holding, bounds), the first implementation must enforce each rule through a single seam, with one test that runs over every site. The rule's site list must come from a search, not from the reported defects. Only then is the bot review a confirmation rather than the discovery mechanism.
