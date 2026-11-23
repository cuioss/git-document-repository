envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:31Z

# Candidate lesson: resolving Q-Gate findings after the 4-plan handshake capture fails the boundary as drift

Signal: script failure, notation `plan-marshall:plan-marshall:phase_handshake` (work log 07:52:51, exit_code=1, failure_kind=script_internal_failure).

## What happened

The 4-plan handshake captured `qgate_open_count=2` and `pending_findings_blocking_count=2` while the two skills_by_profile Q-Gate findings were pending. The operator accepted both findings at 07:52:11-12. The handshake verify at 07:52:51 then reported `status: drift`, drift_count 2, captured "2" and observed "0" for both invariants. The run continued into 5-execute.

## Candidate rule

A drift in the helpful direction (blocking count went DOWN because an operator resolved the findings) is reported as a script failure in the same way a harmful drift is. Two options: (a) the orchestrator re-captures the handshake after an operator resolves boundary findings, before it verifies; or (b) phase_handshake treats a decrease of a blocking-count invariant, explained by recorded resolutions, as non-drift. As it is, every plan where the operator accepts a 4-plan Q-Gate finding produces a false script-failure cluster.

Scope: plan-marshall:plan-marshall phase_handshake / orchestrator boundary order. Framework-side.
