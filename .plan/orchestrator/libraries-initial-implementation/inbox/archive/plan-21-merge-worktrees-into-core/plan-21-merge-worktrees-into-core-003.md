envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:46:23Z

## Candidate lesson: a Sonar finding in moved code cost a full loop-back round

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Phase: 6-finalize sonar-roundtrip and triage.

What happened. The plan moved the git-worktrees code into git-core as the package de.cuioss.git.document.core.worktrees, with no behaviour change. Sonar analysed the moved file as new code and reported java:S4449 (MAJOR code smell) at JGitWorktreeOperations.java:500: a possibly-null `getObjectId()` result was passed to `StartPoint.of` (finding 6fc761, Sonar key AaH5xw0Z3vkKQN2l-ecl). The operator chose to fix it in the PR: read `getObjectId()` once into a local, null-check it, then pass it on (decision log 16:35:34Z). This added TASK-006 and loop-back iteration 1/3 from 6-finalize to 5-execute (commit 6b45edb). It also re-ran the simplify step, the pre-push quality gate, push and CI, and the review-bot round, which then waited out a CodeRabbit rate window of 2100 s.

Why it matters. A move or rename makes every relocated line new code to Sonar, so issues the old module already had reappear on the PR. The finding was only found after the PR was opened, where each fix costs a whole loop-back.

Candidate corrective rule. When a plan moves or renames source files, check the moved files for open Sonar issues (or run the equivalent static check) before the PR is opened. Then either fix them in the move deliverable or record a decision to accept them. Do not find them first in the post-PR sonar-roundtrip.

Likely owning component: plan-marshall:phase-6-finalize (sonar-roundtrip timing), or the phase-3-outline handling of tech_debt / move change types.
