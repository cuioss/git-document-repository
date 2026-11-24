envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:04:48Z

# Candidate lesson: workflow names `--changed-paths-file` for manage-change-ledger append, the installed script accepts only `--changed-paths`

Source: three [ERROR] lines in the work log from plan-marshall:phase-5-execute. They are not counted in signal 3 because they are not marked script_failure, but the same mismatch recurred three times:
- 2026-11-23T18:26:50Z (hash f98a51): "--changed-paths-file rejected as unknown_flag; retrying with --changed-paths"
- 2026-11-23T18:54:33Z (hash a5e7af): "--changed-paths-file not declared (workflow doc names it; installed version accepts --changed-paths)"
- 2026-11-23T19:11:53Z (hash 1701b1): "--changed-paths-file undeclared; retrying with --changed-paths"

Observation: each per-deliverable commit's ledger append failed once and was retried with the other flag. The executing agent itself attributes this to drift between the workflow document and the installed script version (plan-marshall 0.1.2184).

Candidate rule (probably framework-side): bring the phase-5 per-deliverable ledger-append text and the manage-change-ledger argparse surface back in line, in one direction or the other.

Context: plan plan-20-leftover-fixes-2.
