envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:07Z

# Candidate lesson: module_testing task targeted a module POM while the rule under test lived in the root POM

Source: Q-Gate finding df6eb6 (phase 4-plan, module_mapping_validator, deliverable-1), resolution taken_into_account.

## Observation

The module_testing task (TASK-2) declared git-worktrees/pom.xml (module git-worktrees) while its sibling implementation task (TASK-1) declared the root pom.xml (module git-document-repository) that holds the module-layering rule. No row of doc/developer/test-impl-mapping.adoc covers the pair (the file does not exist).

## Resolution in run

D1 touches only pom.xml files (config role, bucket documentation_only): no module_testing profile; the negative run (switch git-testing to compile scope in git-worktrees, expect the enforcer failure, revert) became part of the implementation verification.

## Candidate rule

A build-rule deliverable proven by a deliberate negative run is verified inside the implementation task, not as a separate module_testing task in another module.
