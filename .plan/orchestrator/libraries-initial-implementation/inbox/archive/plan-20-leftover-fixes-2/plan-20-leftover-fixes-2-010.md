envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:31Z

# Candidate lesson: `--number` instead of `--deliverable-number` on manage-solution-outline get-deliverable (script-failure cluster)

Source: script-failure cluster, notation plan-marshall:manage-solution-outline:manage-solution-outline, failure_kind argparse_rejection, exit 2. Work log 2026-11-23T18:29:07Z (hash 6a94f5), at phase-5 re-entry.

Detail: "`--number` is not declared for `plan-marshall:manage-solution-outline:manage-solution-outline get-deliverable`: ['deliverable-number', 'plan-id']".

Correct form: `manage-solution-outline get-deliverable --plan-id P --deliverable-number N`. This is the typed-ID flag convention (argument-naming.md): the flag names the entity.

Context: plan plan-20-leftover-fixes-2.
