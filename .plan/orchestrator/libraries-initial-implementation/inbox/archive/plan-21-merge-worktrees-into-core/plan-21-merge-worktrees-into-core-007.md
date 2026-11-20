envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:51:00Z

## Candidate lesson: the realized footprint drops the source path of every git mv, so moved files read as unrealized

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Found by the plan retrospective (artifact-consistency, manifest-decisions and outline-vs-shipped aspects), not by lessons-capture.

What happened. Deliverables 1 and 2 moved four main classes, package-info.java and six test classes from `git-worktrees/src/.../document/worktrees/` to `git-core/src/.../document/core/worktrees/` by `git mv`. The outline declared both the old and the new paths (43 modification-intent paths). The realized footprint (tier `realized_capture`, 33 paths) lists every new `git-core/...` destination and the plain deletions (for example `git-worktrees/pom.xml`, `GitWorktreesLogMessages.java`, `git-worktrees/src/main/java/module-info.java`), but none of the 10 rename sources. As a result:

- artifact-consistency reports recall 76.7% with exactly those 10 paths missing;
- manifest-decisions rule M6 `declared_vs_realized_set` fails with 10 declared-but-unrealized paths;
- request-result alignment has to explain the gap by hand.

Every one of the 10 "missing" files was in fact changed: it was removed from that path.

Why it matters. Any move or rename plan gets a false declared-vs-realized mismatch, which drowns a real one and pushes coverage toward the 70% bar for no reason. The capture is inconsistent with itself: a deleted path is in the footprint, a renamed-away path is not.

Candidate corrective rule. The realized footprint capture records both sides of a rename (for example from `git diff --name-status -M` with R entries split into old and new path, or with rename detection off), so a rename source counts as realized the same way a deletion does.

Likely owning component: plan-marshall:manage-references (realized footprint capture); the shared footprint resolver in plan-marshall:plan-retrospective consumes it.
