envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:32:00Z

# Candidate lesson: architecture search called with --plan-id before the verb (argparse rejection)

Source: script-failure cluster, notation `plan-marshall:manage-architecture:architecture`. Work-log ERROR at 2026-11-23T20:09:41Z, phase 5-execute (loop-back for fix tasks 8-13).

Failure: `architecture.py --plan-id plan-11-store-anchoring-signed search --content --pattern 'retained|ANCHORING'` returned exit_code=2 with failure_kind=argparse_rejection. The agent put `--plan-id` before the verb as a router-level flag. `architecture` does not take it there, and the call never reached the script body.

A related failure in the same run, not counted in the cluster: at 17:37:46Z, `manage-change-ledger append` was called with `--changed-paths-file`. The executor does not declare that flag (use `--changed-paths`).

Possible lesson: this is the known "invented flag / misplaced --plan-id" signature. The agent should check the verb's own `--help` or its canonical-invocation block instead of carrying `--plan-id` over from the ci-router convention.
