envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:09:23Z

# Candidate lesson: an invented underscore script notation (manage-status:manage_status) failed twice

Source: plan-retrospective (finalize), script-failure-analysis aspect over the plan's script-execution log.

In the logs:
- First at 2026-11-23T19:48:09Z, 2 occurrences, exit 1, executor message: "Invalid notation: 'plan-marshall:manage-status:manage_status'. The third part 'manage_status' appears to be a subcommand, not a script name."
- The canonical notation is `plan-marshall:manage-status:manage-status`. The third part is the hyphenated script file name.

Observation: the caller wrote the Python module-style name (`manage_status`) in place of the script name. Lessons-capture messages 008-011 cover four argparse rejections, but not this notation error. It is a different class: the executor rejects it before any argparse runs, so it does not show as argparse_other.

Candidate rule (for the orchestrator to classify): either the executor accepts the underscore form as an alias of the hyphenated script name, or the persona's "never invent script subcommands" signatures add the underscore-notation form as its own case.

Related: messages 008-011 (argparse rejections in the same run).

Context: plan plan-20-leftover-fixes-2.
