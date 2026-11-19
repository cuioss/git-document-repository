envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:11:09Z

# Candidate lesson: raw worktree paths in INFO log arguments leak personal data (GDR-NFR-3)

- Source: review-bot finding 33fd5a (coderabbitai, PR #30, Major, Security & Privacy), resolution fixed by TASK-8 (a86a1ee)
- File: git-worktrees/src/main/java/de/cuioss/git/document/worktrees/GitWorktreesLogMessages.java:48 (also 55)

## Defect

The add and remove INFO messages took the full worktree path and a caller-derived name as arguments. A path under a
home directory named with an e-mail address puts that address into the log, which GDR-NFR-3 forbids.

## Fix in run

Keep the stable log identifiers, drop the raw path and the derived name from the arguments.

## Possible rule

LogRecord arguments must not carry filesystem paths or caller-supplied names; treat any path argument in a
log template as a GDR-NFR-3 review item when authoring a module's LogMessages holder.
