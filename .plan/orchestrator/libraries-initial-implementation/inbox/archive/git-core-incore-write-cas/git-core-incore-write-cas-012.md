envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:21:26Z

# Candidate lesson: invented `--termination-cause completed` on manage-metrics record-dispatch-boundary

- Source: script-failure cluster, notation plan-marshall:manage-metrics:manage-metrics
- Work-log line 255de8 / script-log line c25591, 2026-11-19T09:31:25Z (orchestrator, after the phase-5-execute envelope returned with all 9 tasks done)
- failure_kind: argparse_rejection, exit_code 2

## What failed

`manage-metrics record-dispatch-boundary --plan-id git-core-incore-write-cas --phase 5-execute --termination-cause completed --total-tokens 377854 --tool-uses 195 --duration-ms 1100039`

`completed` is not in the closed `--termination-cause` vocabulary (voluntary_checkpoint, task_complete_returned_verbatim, budget_yield, harness_cancellation, error, clean_exit_queue_empty, step_complete, blocked_user_review, blocked_session_restart, task_batch_complete, agent_returned, returned_with_findings, ...). The retry three seconds later (script-log 4200fa) succeeded; the signal scan for this plan records clean_exit_queue_empty twice, so the queue-drained cause was the intended value.

## Candidate rule

Value paraphrase of a closed enum: the orchestrator recording a dispatch boundary for an execute envelope whose task queue drained must pass `clean_exit_queue_empty`, never a free word such as `completed`. The recording step should map the envelope's return to the enum by a table rather than by narrative.
