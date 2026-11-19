envelope_version=1
sender_type=plan
sender_id=plan-03-git-worktrees-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T17:10:43Z

# Candidate lesson: a deliverable's success criterion named a file owned by a dependent deliverable

- Source: Q-Gate finding 86382b (phase 3-outline, scope_criterion_validator, under_coverage), resolved taken_into_account
- Plan: plan-03-git-worktrees-import (PR #30)
- File: doc/specification/git-worktrees.adoc

## What happened

Deliverable 3's success criterion required the JGit Status mapping to be stated in
doc/specification/git-worktrees.adoc, but that file was not in deliverable 3's Affected files.
Deliverable 4 (which depends on 3) writes the specification and already carried the same criterion,
so deliverable 3 could not meet its own criterion at completion.

## Resolution in run

Outline left unchanged; task planning scoped deliverable 3 tasks to its Affected files and treated the
specification statement as verified under deliverable 4.

## Possible rule

When outlining, every file a success criterion names must be in that deliverable's Affected files;
a statement written by a later deliverable belongs to that deliverable's criteria only.
