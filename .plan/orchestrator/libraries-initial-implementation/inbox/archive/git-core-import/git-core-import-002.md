envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:43:24Z

# 6-finalize commit gate refuses inline review fixes with no_sanction; --step needs the bare manifest id

Source signal: script-failure cluster `plan-marshall:workflow-integration-git:git-workflow` (2 x exit 1,
`reason: no_sanction`), plan git-core-import, loop-back iteration 2 of wait-region-unified-triage.

## What happened

After the round-2 triage the orchestrator committed 23 inline review fixes from 6-finalize. The source-tree commit
gate refused it twice:

1. No `--step`: refused because there was no step id from the composed phase-6 manifest, no open loop-back fix task
   and no review-rubric section-6 inline-correction record.
2. `--step "plan-marshall:automatic-review"`: refused again with the same reason. The gate compares the value
   against the composed manifest step ids, and those are stored bare (`automatic-review`), without the bundle
   prefix that the work log, the dispatch lines and the step's own frontmatter name show.

The third attempt with the bare form `--step automatic-review` succeeded (commit 98ec1a0).

## Corrective rule (candidate)

- When committing inline fixes in 6-finalize, pass `--step` with the bare manifest step id
  (`automatic-review`, `sonar-roundtrip`), never the `bundle:skill` notation.
- Candidate tooling fix: the gate could normalise a `bundle:step` value to its bare manifest id, or name the
  accepted ids in the refusal payload. Either would turn a blind retry into a one-shot fix.
