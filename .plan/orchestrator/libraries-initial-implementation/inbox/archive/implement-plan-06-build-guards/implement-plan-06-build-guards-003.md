envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:16:55Z

# Candidate lesson: native-image metadata deliverable declared git-core but wrote git-worktrees resources

Source: Q-Gate finding d77466 (phase 3-outline, architecture constraints, severity warning), resolution taken_into_account.

## Observation

Deliverable 3 declared `module: git-core`, but two of its five affected files were git-worktrees/src/main/resources/META-INF/native-image/de.cuioss/git-worktrees/reflect-config.json and resource-config.json. Module-scoped task routing and verification (including jar packaging) would have missed the git-worktrees half.

## Resolution in run

Split into D6 (git-core metadata, module git-core) and D7 (git-worktrees metadata, module git-worktrees), each with its own NativeImageMetadataTest.

## Candidate rule

Same family as 46d99a: one module per deliverable, matching its affected files.
