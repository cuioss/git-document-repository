envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:46Z

component=git-store
category=bug

# The save step's edits skipped the file/directory overlap check, so a Put on a directory deleted its files

Source: review-bot finding 470276 (CodeRabbit, PR #52, ExplicitSave.java:322), fixed by TASK-11.

## Defect

The conflict check looked only at the paths the draft changed. A per-attempt save step could return `Put("dir", ...)` while the base held `dir/keep`. `InCoreWriter` applies a replacing `PathEdit`, so the save silently deleted `dir/keep` instead of returning the overlap conflict that GDR-STORE-5 requires.

## Rule

Run the conflict check on the final merged edit set right before `apply`, not on the draft's edits alone. Every source of edits (draft, step, recovery) has to pass the same check. Test a file-versus-directory overlap explicitly.
