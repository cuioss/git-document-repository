envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:52:39Z

component=pm-dev-java:ext-triage-java
category=improvement

# Fixing Sonar S6539 by moving dependencies into a new class gets the new class flagged again

Source: orchestrator observation in this run (TASK-015 fixed S6539 11cbdf on StoreWriter by extracting StoreClone; TASK-016 then fixed S6539 e3e943 on StoreClone by extracting CloneOpening).

## What happened

S6539 ("monster class", too many class dependencies) on `StoreWriter` was fixed by moving the dependencies into a new class, `StoreClone`. The next Sonar analysis flagged `StoreClone` for the same rule, which cost another loop-back round. Sonar counts the dependencies in a way that could not be reproduced offline, so the fix could not be checked before pushing.

## Rule

An S6539 fix must lower the dependency count of every class it touches, not move the dependencies along. Split by responsibility, so each new class depends on a disjoint subset. Count the distinct imported and referenced types of the new class against the threshold before pushing. If the count cannot be checked locally, take a margin well below the threshold instead of aiming just under it.
