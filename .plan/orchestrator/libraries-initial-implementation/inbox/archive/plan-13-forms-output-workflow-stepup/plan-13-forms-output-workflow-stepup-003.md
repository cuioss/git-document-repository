envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:19:31Z

## Candidate lesson: a green pre-commit gate can leave the working tree dirty

Signal: Q-Gate / verification (phase 5 and phase 6).

What happened: the quality-gate build (`verify -Ppre-commit`) runs OpenRewrite recipes and the license-header plugin. These rewrite formatting and imports in place. The build reported success, but the tree then held uncommitted changes. A commit or push that assumes "green build = clean tree" ships code that differs from what the gate checked, or leaves the plan with a dirty worktree.

Suggested rule: after every green `-Ppre-commit` run, check `git status`. If the profile changed files, review the diff, commit it, and run the gate again before pushing. Treat a dirty tree after the gate as a step that is not finished, not as a pass.

Component hint: plan-marshall:phase-5-execute (verification step) and pm-dev-java:plan-marshall-plugin (Maven pre-commit profile handling).
