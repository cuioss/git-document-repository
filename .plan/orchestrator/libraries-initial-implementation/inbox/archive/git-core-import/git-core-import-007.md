envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:44:26Z

# Outline under-coverage: the "docs agree with code" deliverable missed the README.adoc scaffold statement

Source signal: Q-Gate finding 26b80e (3-outline, `scope_criterion_validator`, under_coverage, deliverable 5),
plan git-core-import. Resolved `taken_into_account` by operator decision.

## What happened

Lines 40-42 of README.adoc said that git-core and git-worktrees are scaffolds, and that the git-core foundation code
would be taken over from its first client in a later step. Deliverables 1-4 did that takeover, so the statement
became wrong. Deliverable 5 only updated the matching Current-state paragraph in AGENTS.md. The operator added
README.adoc to deliverable 5: git-core now holds the taken-over repository part, and git-worktrees stays a scaffold.

## Candidate rule

This is the same defect as finding 900556 (doc/README.adoc). The orchestrator may merge the two candidates. The
module state is stated in AGENTS.md, README.adoc and doc/README.adoc. Every later plan in this epic that moves a
module out of the scaffold state (git-worktrees, and later git-store, git-forms, git-timestamp, git-directory) will
hit the same three-location update. The epic's plan specs could name all three files up front.
