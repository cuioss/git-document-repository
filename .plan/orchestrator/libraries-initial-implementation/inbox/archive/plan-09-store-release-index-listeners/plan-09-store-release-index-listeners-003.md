envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:26Z

component=plan-marshall:manage-findings
category=anti-pattern

# Invented manage-findings verbs and flags were rejected by argparse (qgate query, list)

Source: script-failure cluster `plan-marshall:manage-findings:manage-findings` (2 `[ERROR] script_failure` lines, failure_kind=argparse_rejection).

## What happened

1. 2026-11-21T13:43:12Z: a call used `manage-findings qgate query`. `query` is not a verb. The registered verbs are `add`, `clear`, `list`, `resolve`, `resolve-by-rule` and `resolve-evidenced`.
2. 2026-11-21T16:31:28Z, during the verification-feedback dispatch: a `manage-findings list` call was rejected. The logged usage text was truncated, so the bad flag is not recorded. The accepted flags are `--plan-id`, `--type`, `--resolution {pending,fixed,suppressed,accepted,taken_into_account,rejected}`, `--promoted`, `--file-pattern`, `--component`, `--author`, `--kind`, `--bot-kind` and others.

## Rule

Copy manage-findings subcommands from the skill's Canonical invocations block or from `--help`. Do not paraphrase a verb (`query` instead of `list`).

## Secondary observation

The `[ERROR] script_failure` line cuts the argparse usage text off before the rejected flag. That makes the second failure undiagnosable afterwards. The marker should record the `rejected:` flag name before the usage text.
