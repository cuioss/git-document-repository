envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:01Z

Candidate lesson (script failure: plan-marshall:manage-findings:manage-findings, 3 argparse rejections)

What happened: The manage-findings verbs were invoked with forms that do not exist:
- 10:52:32, 3-outline: `qgate query`. The detail reads "`query` is not a registered verb for ... qgate: ['add', 'clear', 'list', 'resolve', 'resolve-by-rule', 'resolve-evidenced']".
- 19:58:43, automatic-review: `list --limit`, rejected because `--limit` is not declared.
- 21:03:30, after automatic-review: `list --fields`, rejected because `--fields` is not declared. The declared flags are any-checkout, author, bot-kind, component, file-pattern, include-qgate, kind, plan-id, preference-admissible, promoted, resolution and type.

Evidence: [ERROR] script_failure lines c3533b, 6ef3b9 and a6dbfa in the work log (failure_kind=argparse_rejection, exit_code=2).

Suggested rule: Take manage-findings verbs and flags from its Canonical invocations block. `qgate list` is the read verb, and `list` has no output-shaping flags. When the 179 KB list output is too large, narrow it with --type, --resolution or --file-pattern instead of inventing --limit or --fields.
