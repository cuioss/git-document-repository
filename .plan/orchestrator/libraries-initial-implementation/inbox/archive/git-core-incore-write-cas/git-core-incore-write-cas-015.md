envelope_version=1
sender_type=plan
sender_id=git-core-incore-write-cas
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-19T14:26:42Z

component=plan-marshall:phase-4-plan
category=bug
confidence=high
source=plan-retrospective

# Sync Affected files when a Q-Gate under-coverage finding is taken into account

## Context

In plan git-core-incore-write-cas the 4-plan Q-Gate raised two under-coverage findings: 04f7cd (root README.adoc states the git-core state but is not in deliverable 4's affected files) and acb2bd (RecordingTransportProtocol.java must change to observe the transport timeout but is in neither deliverable 5's affected files nor task 8's steps). Both were resolved `taken_into_account`. Both files were then modified, yet neither was ever added to the deliverables' declared files, so the retrospective's manifest rule M6 reports exactly these two paths as realized-but-undeclared.

## Root cause

Resolving an under-coverage finding as `taken_into_account` only adjusts the task description. Nothing requires the remedy the finding itself names ("add the file to the deliverable and the task, or document the exclusion") to reach the outline's Affected files and references.json, so the finding is closed while the declaration gap it was raised for stays open.

## Proposed action

When a Q-Gate finding of the under-coverage class is resolved `taken_into_account` (or `fixed`), require the resolver to either add the named path to the deliverable's Affected files (and re-run sync-affected-files) or record an explicit exclusion; reject the resolution otherwise. The 3-outline counterparts (lesson-capture messages 001-004 cover the findings' content, not this closure gap) should follow the same rule.

## Evidence

- aspect: manifest-decisions — declared_vs_realized_set fail, culprits README.adoc and RecordingTransportProtocol.java
- aspect: artifact-consistency — references_only [README.adoc, RecordingTransportProtocol.java]
- aspect: request-result-alignment — both scope-creep entries trace to findings 04f7cd and acb2bd
