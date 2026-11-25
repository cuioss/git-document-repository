envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:07Z

Candidate lesson (script failure: plan-marshall:plan-orchestrator:orchestrator, inbox detect without --source-id)

What happened: At 08:03:37 on 2026-11-25, right after branch-cleanup merged PR #76, `orchestrator inbox detect` was called without its required flag and rejected: "Add the required flag(s) to `plan-marshall:plan-orchestrator:orchestrator inbox detect`: ['source-id']" (exit_code=2).

Evidence: [ERROR] script_failure line 9e062f in the work log (failure_kind=argparse_rejection). signal-scan lists this notation as the fifth failing notation; it falls after the dispatcher's earlier count of four.

Suggested rule: The log does not name the caller. Whoever resolves orchestration context must read `source_id` first through `manage-plan-documents request read --section source_id`, then pass it as `--source-id`. Never call `inbox detect` without that value.
