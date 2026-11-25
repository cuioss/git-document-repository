envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:19:21Z

## Candidate lesson: manage-change-ledger append rejects the flag the phase-5 workflow prescribes

Signal: script failure cluster.

What happened: the phase-5 workflow tells the caller to run `manage-change-ledger append` with `--changed-paths-file`. The installed script rejects that flag as an unrecognized argument (exit code 2). The call works only with `--changed-paths`.

Suggested rule: until the workflow and the script agree, call `append` with `--changed-paths`. Fix the drift at the source: either add `--changed-paths-file` to the script's parser, or correct the phase-5 workflow text and its canonical-invocation block.

Component hint: plan-marshall:manage-change-ledger (also plan-marshall:phase-5-execute).
