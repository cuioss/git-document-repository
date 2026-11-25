envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:25:53Z

## Candidate lesson: the scope-creep check flags the plan's own review fix tasks

Signal: retrospective (artifact consistency, manifest cross-check, Q-Gate finding 50f3c7).

What happened: the finalize review rounds created fix tasks 14-27. Together they changed 15 files outside the outline's declared surface: 5 git-store files for the operator-approved pinned release, and 10 git-forms files for review fixes and the S6539 split of `DocumentTransitions` into 7 classes. On the last return to 5-execute, `scope_creep_check` filed a blocking `scope_creep_warning` (8 files, threshold 5). An agent then accepted it by hand, with the reason "added by fix tasks 14-27 for PR 64 review and Sonar findings". The check graded work that triage itself had asked for, and that work's scope was already recorded on the fix tasks.

Suggested rule: when triage creates a fix task, add the fix task's target files to the plan's declared scope (references `affected_files`, marked as coming from triage). The scope-creep check then reports only files that no deliverable and no fix task declared. A fix task that needs files in another module (here git-store) should still surface that module boundary to the operator. Do not drop that part.

Component hint: plan-marshall:phase-5-execute (scope_creep_check) and the finalize triage fix-task creation.
