envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:33Z

# Candidate lesson: phase-5 leaf repeatedly calls get-deliverable with an invented --number flag

Signal: script failure, notation `plan-marshall:manage-solution-outline:manage-solution-outline` (script log 08:29:53, 08:47:37, 09:33:12; exit_code=2, argparse_rejection).

## What happened

In three separate phase-5 envelopes the leaf called `get-deliverable --plan-id plan-23-forms-hardening --number N`. The verb declares `--deliverable-number` and `--plan-id` only. Each time the leaf retried correctly within seconds, so nothing was lost, but the same wrong flag came back in every new envelope.

## Candidate rule

The mistake recurs across envelopes, so the lesson does not carry over from the earlier rejection. Either the phase-5 workflow text that leads to this call should quote the canonical `get-deliverable --plan-id P --deliverable-number N` form verbatim, or the verb should accept `--number` as an alias. The second option conflicts with the typed-ID flag naming standard (`--deliverable-number`), so the first is preferred.

Scope: plan-marshall:phase-5-execute / execute-task workflow text that reads a deliverable.
