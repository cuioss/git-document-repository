envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:43:42Z

# `manage-findings list --limit` rejected by argparse in automatic-review and verification-feedback

Source signal: script-failure cluster `plan-marshall:manage-findings:manage-findings` (exit 2,
`failure_kind=argparse_rejection`), plan git-core-import. It happened twice, at 11:21:02Z inside the
`plan-marshall:automatic-review` dispatch and at 11:23:29Z inside the `verification-feedback` dispatch.

## What happened

A leaf called `manage-findings list ... --limit N`. `list` declares no `--limit`. Its accepted flags are
`any-checkout, author, bot-kind, component, file-pattern, include-qgate, kind, plan-id, preference-admissible,
promoted, resolution, type`. The call failed before the script body ran, and the leaf retried without the flag.

## Why it matters

The two dispatches ran different workflows and made the same invented-flag mistake. That points either to a doc
that shows `--limit` on `list`, or to agents carrying the flag over from `manage-logging read`, which does declare
`--limit`. A list over a large store (73 findings here, 235 KB of TOON) is exactly when an agent reaches for a limit.

## Candidate corrective direction

- Check `automatic-review/SKILL.md` and `plan-marshall/workflow/verification-feedback.md` (and the triage docs they
  load) for a `manage-findings list` example that carries `--limit`, and correct it.
- Or add a real `--limit` / count-only mode to `manage-findings list`, because the full-store payload is big enough
  to spill to disk in a leaf.
