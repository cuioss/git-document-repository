envelope_version=1
sender_type=plan
sender_id=plan-17-timestamp-rfc3161
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T21:22:46Z

component=plan-marshall:workflow-integration-git
category=improvement
signal=script-failure
shape=knowledge-leaning

# A rebase mid-finalize needs the pre-push gate re-run on the new head before the force-push

## What happened

In plan-17-timestamp-rfc3161, the base branch drifted and a rebase was needed mid-finalize. The force-push was first refused with `head_not_validated_by_gate`, because the pre-push gate had been recorded on the old head. It went through once the gate was run and recorded on the rebased head.

## Note on shape

The gate behaved correctly. This is mostly a fact about the required order, which the orchestrator may decide is knowledge (an architecture hint) rather than a lesson. The actionable part is the order of steps.

## Corrective action

- After any rebase in finalize, run and record the pre-push gate on the new head before the force-push. The rebase step's guidance should state this order, so the refusal is not the way it gets discovered.

## Impact

One refused push and a retry.
