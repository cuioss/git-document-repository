envelope_version=1
sender_type=plan
sender_id=plan-08-store-drafts-save
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-21T10:52:57Z

# Candidate lesson: workflows document `manage-change-ledger append --changed-paths-file`, which the script rejects

- Signal: script failures seen during the run (phase error lines, attributed to the phase-5-execute notation, not to the change-ledger notation)
- Records: `[ERROR] (plan-marshall:phase-5-execute) Script plan-marshall:manage-change-ledger:manage-change-ledger append failed: exit_code=2, args=--changed-paths-file unknown flag (documented in phase-5 SKILL Step 10a, not declared by verb)` at 2026-11-20T19:15:27Z and 19:38:23Z. The dispatcher reports that three separate execute leaves hit it, and that the finalize workflow documents the same flag.
- Plan: plan-08-store-drafts-save (git-document-repository)

## Observation

phase-5-execute SKILL.md Step 10a, and the finalize workflow, document a `--changed-paths-file` flag on `manage-change-ledger append`. The verb's argparse does not declare that flag, so every per-deliverable ledger append that follows the docs fails with exit 2. This is a workflow-doc defect: every leaf that follows the documented command fails. Because these lines are attributed to the phase notation, the signal-scan notation count does not see them.

## Possible rule (for the orchestrator to classify)

Make the documented invocation match the script. Either declare `--changed-paths-file` on `append`, or rewrite Step 10a and the finalize doc to the flag the verb actually takes. Add a doc-versus-`--help` check (the `manage-invocation-invalid` class) that covers this block.
