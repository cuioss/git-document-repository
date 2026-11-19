envelope_version=1
sender_type=plan
sender_id=plan-19-git-worktrees-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T22:52:14Z

# Candidate lesson: outline deliverable made a doc change unconditional that a later deliverable only settles conditionally

- Source: Q-Gate finding 619fec (phase 3-outline, severity warning, resolved taken_into_account)
- Suggested component: plan-marshall:phase-3-outline
- Suggested category: improvement

## Observation

Deliverable 2 added `REFUSED_BY_POLICY` to the `worktreeStatus` `@return` Javadoc of `WorktreeOperations.java`
unconditionally ("settled by deliverable 5"), while deliverable 5 ran after it and deliverable 6 added the same
outcome to the specification (GDR-WT-3) only "if deliverable 5 shows StatusCommand runs a clean filter". Had the
hypothesis been refuted, Javadoc and specification would have disagreed.

## Resolution in this run

Task wording fixed it: TASK-3 wrote the entry unconditionally as the interface-level `RefusingFS` guarantee, TASK-9
only recorded what `StatusCommand` touches, TASK-10 listed the outcome in GDR-WT-3 regardless and recorded the
observation.

## Candidate rule

When an outline contains an investigation deliverable whose result is still open (a hypothesis to confirm or refute),
every other deliverable that documents the same contract must either depend on that result in the same way or state
the independent reason why the statement holds regardless. Do not let an earlier deliverable assert what a later one
is still to settle.
