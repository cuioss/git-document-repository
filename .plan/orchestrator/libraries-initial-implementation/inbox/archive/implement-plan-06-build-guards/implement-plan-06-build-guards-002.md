envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:16:52Z

# Candidate lesson: deliverable declared one module while its affected files spanned three

Source: Q-Gate finding 46d99a (phase 3-outline, architecture constraints, severity warning), resolution taken_into_account.

## Observation

Deliverable 2 declared `module: git-core` but its affected files included DomainTermAbsenceTest.java in git-worktrees and in git-testing. Task planning resolves module-scoped build/test commands and skills from that field, so a module_testing task narrowed to git-core would not have run the other two tests.

## Resolution in run

Split: D2 became git-core/pom.xml only; the domain-term tests became D3 (git-core), D4 (git-worktrees), D5 (git-testing), each declared against its own module.

## Candidate rule

Every affected file of a deliverable belongs to the module the deliverable declares; a per-module check repeated across modules is one deliverable per module. (Sibling findings: d77466, df6eb6, 905254, 5c711d in this plan show the same family.)
