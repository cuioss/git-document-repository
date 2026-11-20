envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:21Z

# Candidate lesson: invented flag `--limit` on manage-findings list

**Source:** script_failure marker (work log, 2026-11-20T11:44:57Z, automatic-review round 2)
**Notation:** plan-marshall:manage-findings:manage-findings
**Failure kind:** argparse_rejection, exit_code=2

## Observation

The automatic-review leaf called `manage-findings list ... --limit N`. `list` declares no `--limit`; its flags
are any-checkout, author, bot-kind, component, file-pattern, include-qgate, kind, plan-id,
preference-admissible, promoted, resolution, type. The full `list` output is also large (82 KB for 15 findings
of this plan), which is the likely reason a limit was reached for.

## Candidate rules

1. `manage-findings list` has no `--limit`; narrow with `--type`, `--resolution`, `--bot-kind` or
   `--file-pattern` instead.
2. Possible improvement: a projection or limit option on `list`, since the `raw_input` / `body` columns make
   unfiltered output too large for a leaf's context.
