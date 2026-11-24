envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:38Z

# Candidate lesson: undeclared `--worktree-path` on manage-status mark-step-done (script-failure cluster)

Source: script-failure cluster, notation plan-marshall:manage-status:manage-status, failure_kind argparse_rejection, exit 2. Work log 2026-11-24T06:40:03Z (hash f219c5), in the finalize-step-simplify re-fire after the branch-cleanup rebase loop-back.

Detail: "`--worktree-path` is not declared for `plan-marshall:manage-status:manage-status mark-step-done`: ['display-detail', 'fact', 'force', 'head-at-completion', 'loop-back-target', 'no-completion-log', 'outcome', 'phase', 'plan-id', 'step']".

Correct form: `mark-step-done --plan-id P --phase 6-finalize --step S --outcome O [--force] [--display-detail D]`. Under the cwd-pinned model the worktree path is never forwarded to manage-status.

Context: plan plan-20-leftover-fixes-2. The step then completed (outcome=done at 06:40:06Z).
