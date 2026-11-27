envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:56:16Z

# Candidate lesson: `architecture --plan-id ... search --content` rejected

- Source signal: script failure cluster, notation plan-marshall:manage-architecture:architecture (argparse_rejection, exit 2), phase 5-execute, 2026-11-27T16:23:05Z
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:manage-architecture

## Observation

The call `architecture.py --plan-id plan-14-forms-deadlines-links-numbers search --content --pattern 'GDR-FORMS-(6|8|9)'` was
rejected by argparse. The logged detail does not name the offending token; likely candidates are the router-position
`--plan-id` (not declared at top level for `architecture`) or the regex alternation in the pattern.

## Possible rule

Forward `--plan-id` only where the script's own parser declares it and in the position it requires; `architecture search`
should be invoked per its canonical-invocation block. Also: the failure detail for argparse rejections of this script should
name the rejected token, as the other notations' details do.
