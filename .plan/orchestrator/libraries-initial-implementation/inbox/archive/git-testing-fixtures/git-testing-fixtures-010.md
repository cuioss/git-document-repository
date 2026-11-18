envelope_version=1
sender_type=plan
sender_id=git-testing-fixtures
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T06:53:01Z

# Candidate lesson: invented verb `manage-findings qgate query` (argparse rejection, twice)

- Source signal: script-failure cluster, notation plan-marshall:manage-findings:manage-findings (by_class: script_failure 2, failed 0, no_progress_reclassification 0)
- Work-log entries: hash c3533b at 2026-11-17T19:43:02Z and 2026-11-17T19:45:39Z, both during 3-outline re-entry after Q-Gate finding 9fe2d8
- Detail: exit_code=2, failure_kind=argparse_rejection. "`query` is not a registered verb for `plan-marshall:manage-findings:manage-findings qgate`: ['add', 'clear', 'list', 'resolve', 'resolve-by-rule', 'resolve-evidenced']"

## What happened

Within one plan, an agent called `manage-findings qgate query` two times, about 2.5 minutes apart. That verb does not exist. The correct read verb is `qgate list --plan-id P --phase PH [--resolution R]`. The first rejection did not stop the second attempt.

## Candidate rule

This is the "verb paraphrase" form of the invented-subcommand error, on the Q-Gate read path of phase-3-outline / q-gate re-entry. Possible fixes are a canonical-invocation reference to `qgate list` at the point where phase-3-outline reads pending Q-Gate findings, or a `query` alias. The run also had 3 verification_handback rows, which are not lesson-bearing and are left out here.

Classification is deferred to the orchestrator.
