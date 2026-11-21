envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:52:53Z

# Candidate lesson: phase-3 outline issues `manage-findings qgate query`, a verb that does not exist

- Signal: script-failure cluster (signal 3), notation `plan-marshall:manage-findings:manage-findings`
- Records: 3 `[ERROR] script_failure` lines, `failure_kind=argparse_rejection`, two of them from the phase-3-outline leaf (2026-11-20T18:40:30Z and 18:53:48Z)
- Detail: "`query` is not a registered verb for `manage-findings qgate`", registered verbs are add, clear, list, resolve, resolve-by-rule, resolve-evidenced
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

The outline leaf reached for `qgate query`, a verb that sounds right but does not exist, on two separate dispatches. The call failed at argparse (exit 2) and the leaf recovered. The failure repeated on re-entry, so the wrong verb comes from something the leaf reads (workflow prose or a standard), not from a one-off slip.

## Possible rule (for the orchestrator to classify)

Find where phase-3-outline, or a standard it loads, names or suggests a `qgate query` read. Replace it with `qgate list --phase ... [--resolution ...]`, or add `query` as an accepted read alias.
