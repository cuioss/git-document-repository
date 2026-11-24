envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:03:57Z

# Candidate lesson: cross-module test migration placed in a task of the wrong module (Q-Gate afd2ca)

Source: Q-Gate finding afd2ca, phase 4-plan, module_mapping_validator, test/impl module mismatch for deliverable 5, resolution taken_into_account.

Observation: module_testing TASK-10 declared ten git-core test files while its sibling implementation TASK-9 declared only git-testing files. The git-core tests migrate absence assertions onto the new git-testing generator and fixture; they do not exercise the changed git-testing code. No doc/developer/test-impl-mapping.adoc exists to record such a pairing.

Resolution in run: the ten git-core tests were split into TASK-13 (module_testing, module git-core, depends on TASK-9); TASK-10 kept the six git-testing tests; manifest recomposed with envelope_count 4.

Candidate rule: when a deliverable adds a helper in module A and migrates consumers in module B, plan the consumer migration as its own module_testing task in module B from the start.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
