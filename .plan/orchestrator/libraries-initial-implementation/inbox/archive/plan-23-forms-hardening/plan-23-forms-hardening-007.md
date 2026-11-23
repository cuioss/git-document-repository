envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:39Z

# Candidate lesson: pre_merge_gate refuses on an OPTIONAL bot that review_completeness counts as complete

Signal: wait-region / branch-cleanup merge gate (decision log 11:38:05 and 12:00:50; PR #59).

## What happened

`pre_merge_gate merge --route merge-queue` refused with `merge_participation_unproven` (reason=participation_unproven, authorizable=true, unproven_bots=sourcery). States: coderabbit=participated, sourcery=refused_structural (150000-diff-character cap, measured 2913 changed lines). sourcery is in `optional_bots`, and the barrier-side `review_completeness` check had reported `participation_complete=true`. The two gates disagree on the same PR and head (43dad67). The merge was blocked until the operator selected "Accept the coverage gap (record reason)", which granted `barrier-ask-override` over `review-barrier-gap` at 43dad67. PR #59 then merged via the queue as bdbb5e4.

The orchestrator also reports that the grant verb accepts only the gate's own override labels, so an operator answer to a question phrased differently could not be turned into a ruling, and the question had to be asked again in the gate's own terms. The logs show that the operator's final selection used the gate's label. This capture did not see the earlier question, so that part is orchestrator-reported.

## Candidate rule

An optional bot's structural refusal should not block the merge gate when the completeness check has already counted participation as complete. One predicate should decide both gates. Until it does, the operator prompt at this refusal must be phrased with the gate's own override labels, or the answer cannot be granted.

Scope: plan-marshall:phase-6-finalize branch-cleanup pre_merge_gate vs review_completeness. Framework-side.
