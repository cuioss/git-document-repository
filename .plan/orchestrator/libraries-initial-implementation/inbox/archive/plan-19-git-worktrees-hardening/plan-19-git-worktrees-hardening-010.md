envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:53:32Z

# Candidate lesson: CodeRabbit's included-review quota (1 per hour) refuses the review of a loop-back fix commit

- Source: automatic-review re-run after the TASK-11 loop-back (work log 2026-11-18T22:10:31Z to 22:46:05Z; a ~31 minute lock-waiting window 22:14:43Z to 22:45:40Z); the f1ecca review body states "Your plan provides up to 1 included review per hour; 0 remain after this review"
- Suggested component: plan-marshall:automatic-review (and the cross-plan review-bot rate-window claim in plan-marshall:manage-locks)
- Suggested category: improvement

## Observation

The first CodeRabbit review on PR #31 (at 43afa9a, ~21:43Z) used the only included review for the hour. The fix
commit 9b4f3b8 from loop-back TASK-11 was pushed about 25 minutes later, and CodeRabbit refused to review it. The
run settled it with `accept_review_on_record` after waiting out the window, which cost roughly half an hour of
finalize wall time.

## Candidate rule

When a loop-back fix is pushed inside the review bot's rate window, the automatic-review step should know the
window from the previous review's quota line (or the rate-window claim) and decide up front: wait until the window
opens and request the review, or accept the review on record for the earlier head with the fixed findings listed.
Record the window end so the wait is bounded and visible rather than discovered through a refusal. Across plans of
one epic, the hourly quota is a shared resource; staging PRs so that two do not land in the same hour avoids the
wait.
