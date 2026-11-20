envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:16:59Z

# Candidate lesson: outline referenced a batched question that was never recorded

Source: Q-Gate finding 5ecc43 (phase 3-outline, request alignment, severity warning), resolution taken_into_account.

## Observation

Deliverable 3's "Change per file" for git-worktrees said the files would carry empty arrays "(see the batched question on this point)", but no such question existed anywhere in solution_outline.md. Whether git-worktrees ships empty metadata files or none was left open and untraceable.

## Resolution in run

The owner decided git-worktrees ships META-INF/native-image/de.cuioss/git-worktrees/ with empty arrays; D7 stated it with the reason, D8 wrote it into GDR-NFR-4, and the dangling reference was removed.

## Follow-on (see also the execute-phase BLOCKED record)

In execute, TASK-8 found that an empty array is NOT a valid resource-config.json: GraalVM's resource-config schema v1.1.0 requires a top-level object, and ResourceConfigurationParser (graal release/graal-vm/23.1) rejects a non-object document. reflect-config.json `[]` is valid. The decided content was therefore wrong for one of the two files; TASK-8/TASK-9 were removed after research. The owner decision was taken on an unverified format assumption.

## Candidate rule

An outline never points at a question it has not recorded; and a decision about the content of an external-format file (here GraalVM metadata) is checked against that format's schema before it is frozen into the outline.
