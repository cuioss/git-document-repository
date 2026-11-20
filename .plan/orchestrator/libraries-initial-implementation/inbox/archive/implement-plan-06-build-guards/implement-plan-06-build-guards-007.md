envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:10Z

# Candidate lesson: test task covered modules its sibling implementation task did not change

Source: Q-Gate finding 905254 (phase 4-plan, module_mapping_validator, deliverable-2), resolution taken_into_account.

## Observation

The module_testing task (TASK-4) declared DomainTermAbsenceTest.java in git-worktrees and git-testing, while the sibling implementation task (TASK-3) declared only git-core/pom.xml. The two tests exercise their own modules' public types, not the changed consumer-isolation rule.

## Resolution in run

The domain-term tests moved into test_only deliverables D3/D4/D5, one per module; D2 became the git-core POM rule alone.

## Candidate rule

Same family as the outline findings 46d99a / d77466: the 4-plan mapping validator caught again what the outline module declarations had already gotten wrong. Fixing the deliverable-to-module mapping at outline time would have avoided both rounds.
