envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:50:47Z

## Candidate lesson: automatic-review armed a 35-minute rate-window wait when the required review was already on record

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Phase: 6-finalize automatic-review, iteration 2 (after the Sonar loop-back). Found by the plan retrospective, not by lessons-capture.

What happened. After the loop-back commit 6b45edb, automatic-review iteration 2 returned `escalate_ask` with `reason: rate_window_await` and claimed a 2100 s CodeRabbit rate window. In the same pass it had already established that no re-review was owed: `participation_complete: true` (coderabbit and sourcery both `participated`), CodeRabbit was in `review_complete_bots` for 6f5b7cd, `re_review_on_loopback: false`, and `required_stale_bots` was empty, so nothing was re-triggered. The claim was made anyway because the recovery-action consult answered `unmeasured, no_window_observation` and routed to Branch 2. The orchestrator then waited (work log 16:55:20Z to 17:31:06Z, title token lock-waiting). Iteration 3 found its own elapsed claim, `recovery-action` returned `accept_review_on_record` (`review_on_record_done`, 0 pending CodeRabbit findings), and posted nothing. The leaf itself noted that the Branch 3 re-consult with `--finished-review true` would likely do exactly that.

Why it matters. The 35-minute wait (2100 s, more than the 33m23s of idle metrics.md attributes to the closed phases) bought nothing: the review that the wait protected was never requested. Any loop-back that touches a PR where the required bot hit its quota will repeat this.

Candidate corrective rule. Before arming a rate-window claim, automatic-review checks whether a re-review is owed at all (re_review_on_loopback, required_stale_bots, finished review on record). When none is owed, it takes the accept-review-on-record path directly (pass `--finished-review true` to the first recovery-action consult) instead of claiming and waiting.

Likely owning component: plan-marshall:automatic-review (refusal-recovery branch selection, recovery-action consult inputs).
