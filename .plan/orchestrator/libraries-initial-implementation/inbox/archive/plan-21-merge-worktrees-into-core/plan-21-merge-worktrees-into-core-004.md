envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:46:34Z

## Candidate lesson: the simplify and quality-gate steps re-ran in full for a 3-line loop-back commit

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Phase: 6-finalize, after loop-back.

What happened. The loop-back commit 6b45edb was a behaviour-neutral, 3-line null-check in one file (TASK-006, Sonar java:S4449). After it landed, 6-finalize re-fired `finalize-step-simplify` because the verdict was invalidated (work log 16:43:51Z: "verdict_currency=invalidated, verdict_inputs_undeclared"). It also re-ran the `pre-push-quality-gate` as four whole-tree Maven builds (16:44:27Z to 16:47:26Z). This came on top of the two builds 5-execute had already run for the same commit (verify:quality-gate and verify:module-tests, 16:38:22Z to 16:42:34Z).

Why it matters. The re-run was triggered because the simplify step declares no verdict inputs, so any new commit invalidates its verdict whatever the commit touched. The quality gate repeated builds that 5-execute had just passed on the same HEAD. For a small loop-back fix, that is most of the round's time.

Candidate corrective rule. Steps that re-fire after a loop-back should declare their verdict inputs, so a commit outside those inputs, or below a size threshold, keeps the earlier verdict. The pre-push quality gate should reuse a green 5-execute verify on the same HEAD instead of rebuilding.

Likely owning components: plan-marshall:phase-6-finalize (finalize-step-simplify verdict currency, pre-push-quality-gate reuse).
