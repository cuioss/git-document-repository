envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:14Z

# Candidate lesson: shipped metadata files exercised by no test in their module

Source: Q-Gate finding 5c711d (phase 4-plan, module_mapping_validator, deliverable-3), resolution taken_into_account.

## Observation

The module_testing task (TASK-6) declared git-core NativeImageMetadataTest.java, while its sibling implementation task (TASK-5) also wrote git-worktrees native-image reflect-config.json and resource-config.json. The test reads only the git-core metadata, so the git-worktrees files were covered by no test. Additionally, `architecture which-module` returned module null for all four META-INF/native-image resource paths.

## Resolution in run

git-worktrees metadata split into D7 with its own NativeImageMetadataTest (later dropped when the empty resource-config.json was found invalid; see the outline finding 5ecc43 candidate).

## Candidate rule

Every shipped resource file has a test in its own module. Separately: `architecture which-module` does not attribute src/main/resources/META-INF/native-image paths to their module, which weakens module-mapping validation for resource-only changes.
