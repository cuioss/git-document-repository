envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:04Z

Candidate lesson (script failure: plan-marshall:manage-solution-outline:manage-solution-outline, same rejection twice)

What happened: Two phase-5 envelope re-entries (13:21:29 and 13:55:59) each called `get-deliverable --number N`. Argparse rejected both: "`--number` is not declared for ... get-deliverable: ['deliverable-number', 'plan-id']".

Evidence: [ERROR] script_failure line 6a94f5, logged twice with an identical detail (failure_kind=argparse_rejection, exit_code=2).

Suggested rule: The flag is `--deliverable-number`. The identical failure recurred in a fresh envelope, so the phase-5 re-entry instructions (or the doc that leads the agent to `--number`) should spell out the canonical invocation instead of leaving it to inference.
