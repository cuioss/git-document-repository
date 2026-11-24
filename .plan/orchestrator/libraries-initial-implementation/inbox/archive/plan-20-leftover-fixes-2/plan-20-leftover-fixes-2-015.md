envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:05:17Z

# Candidate lesson: pre_merge_gate record-merge refused the true "rebased + merge_queue" combination (action_on_queue_route)

Source: reported by the orchestrator for this run. NOT confirmed in the plan logs read for this capture: the work and decision logs show the route but contain no record-merge refusal line. The script-execution log was not searched.

Route evidence that is in the logs:
- 2026-11-24T06:27:06Z decision: use_merge_queue=true was overridden by queue_wait=forbidden from the conflict gate. Worktree-rebase-to and force-push-with-lease ran, then the authoritative CI wait.
- 06:57:33Z decision: "PR #69 merged by the platform merge queue as f7eda4e ... after 144s".
- 07:01:24Z work: "PR #69 enqueued via pr merge-queue and corroborated merged by the platform queue (f7eda4e, after a rebase onto main authorized by the operator)".

Reported fact: recording the merge with the true facts (rebased, then landed through the merge queue) was refused with action_on_queue_route, so branch-cleanup was closed with a direct mark-step-done instead of the gate-recorded merge.

Observation: the real route mixed a rebase override (queue_wait=forbidden) with a queue landing. The record-merge validator seems to treat those two facts as mutually exclusive, so a correct landing could not be recorded truthfully.

Candidate rule (framework-side, for the orchestrator to check against the script log): the merge-authorization record accepts a rebase followed by a queue landing, or the conflict-gate override does not route the merge through the queue.

Context: plan plan-20-leftover-fixes-2, PR 69.
