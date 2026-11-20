envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:46:50Z

## Candidate lesson: manage-findings qgate calls were rejected by argparse (an invented verb, then a missing flag)

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Signal: script-failure cluster, notation plan-marshall:manage-findings:manage-findings (signal-scan: script_failure class).

What happened. Two calls to the same notation in this run were rejected by argparse (exit 2):

1. 3-outline re-entry, work log 2026-11-20T15:31:29Z (hash c3533b): a call to `manage-findings qgate query`. `query` is not a registered verb. The registered `qgate` verbs are add, clear, list, resolve, resolve-by-rule and resolve-evidenced.
2. 6-finalize lessons-capture, work log 17:45:06Z (hash 771256): a call to `manage-findings qgate list --plan-id ...` without `--phase`, which `qgate list` requires. This was the lessons-capture leaf itself, reading the Q-Gate findings to write these candidates. It retried with `--phase 3-outline`.

Why it matters. The first is the verb-paraphrase signature (a likely-sounding verb taken from the prose, not from the script's surface). The second is the missing-required-flag signature: a reader wants every phase at once, but `qgate list` only answers per phase. Both cost a retry. The second also means a caller that wants Q-Gate findings across all phases has to make five calls.

Candidate corrective rule. Take the verb and flag set from the script's canonical-invocation block or its `--help`, never from workflow prose. For the owning component, consider letting `qgate list` omit `--phase` to list every phase, because the lessons-capture body reads Q-Gate findings across 2-refine through 6-finalize.

Related, outside this signal's count: `manage-change-ledger append` was also rejected twice with exit 2 on an undeclared `--changed-paths-file` and retried with `--changed-paths` (work log 15:57:45Z and 16:40:28Z, phase-5-execute). Those were logged as plain [ERROR] lines, not script_failure markers.

Likely owning components: plan-marshall:manage-findings (qgate list surface), plan-marshall:phase-3-outline (the call site of the invented verb), plan-marshall:phase-5-execute (the change-ledger call site).
