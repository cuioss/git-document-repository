envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:24Z

# Candidate lesson: undeclared `--field` on manage-tasks read (script-failure cluster)

Source: script-failure cluster, notation plan-marshall:manage-tasks:manage-tasks, failure_kind argparse_rejection, exit 2. Work log 2026-11-23T17:51:21Z (hash e833b0) and 17:51:26Z (hash 1495d7), during phase-5 execute, while computing the per-deliverable commit chain tail.

Detail: "`--field` is not declared for `plan-marshall:manage-tasks:manage-tasks read`: ['plan-id', 'task-number']". Attempted args: `--task-number 5 --field depends_on`.

Correct form: `manage-tasks read --plan-id P --task-number N`, then read `depends_on` from the returned TOON. The read verb has no field selector.

Context: plan plan-20-leftover-fixes-2.
