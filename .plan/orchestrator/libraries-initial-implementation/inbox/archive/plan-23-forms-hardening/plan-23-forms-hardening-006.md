envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:38Z

# Candidate lesson: architecture --plan-id placed after the verb (misplaced router flag)

Signal: script failure, notation `plan-marshall:manage-architecture:architecture` (script log 09:03:18, exit_code=2, error misplaced_router_flag).

## What happened

The leaf called `architecture search --content --pattern '...' --plan-id plan-23-forms-hardening`. `--plan-id` is a top-level router flag of architecture.py and must come BEFORE the verb. The script's own error names the correct form (`architecture.py --plan-id P search --content --pattern ...`). The leaf retried correctly 34 seconds later, and later calls in the run used the pre-verb form.

## Candidate rule

This is the known "router-scoped --plan-id after the verb" argparse signature. It recurred here on the architecture router, not only on ci. A single occurrence that recovered on its own has low weight. Record it as a recurrence count against the existing signature, not as a new lesson.

Scope: plan-marshall:manage-architecture invocation discipline (agent-behavior-rules recurrence signatures).
