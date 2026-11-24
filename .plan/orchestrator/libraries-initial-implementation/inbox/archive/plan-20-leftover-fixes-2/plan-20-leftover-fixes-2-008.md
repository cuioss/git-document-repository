envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:17Z

# Candidate lesson: invented verb `qgate query` on manage-findings (script-failure cluster)

Source: script-failure cluster, notation plan-marshall:manage-findings:manage-findings, failure_kind argparse_rejection, exit 2. Seen twice (work log 2026-11-23T16:50:34Z and 16:51:28Z, hash c3533b), around the outline Q-Gate re-entry.

Detail: "`query` is not a registered verb for `plan-marshall:manage-findings:manage-findings qgate`: ['add', 'clear', 'list', 'resolve', 'resolve-by-rule', 'resolve-evidenced']".

Correct form: `manage-findings qgate list --plan-id P --phase PHASE [--resolution R]`.

The same invented verb failed twice in one minute, so the first rejection did not correct the caller. This is the known verb-paraphrase signature. Possibly a framework candidate: find the workflow text that suggests "query".

Context: plan plan-20-leftover-fixes-2.
