envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:05:27Z

# Candidate lesson: the quality-gate hand-back needed an orchestrator-side commit, and the commit verb failed on an unstaged index

Source: reported by the orchestrator for this run, partly confirmed by the plan logs.

In the logs:
- signal-scan reports verification_handback_rows: 2 (prescribed hand-backs, not counted as failures).
- 2026-11-23T22:44:09Z decision: "Whole-tree verify:quality-gate resolves per_task live, but is handed back to the orchestrator per the dispatch instruction (verification_handback), not run in the leaf."
- 2026-11-23T22:26:36Z decision: inline triage edits were committed as b8e378c and the push was deferred because HEAD had moved past the pre-push-quality-gate stamp.

Reported fact, NOT confirmed in the work or decision log: after the handed-back quality gate (the -Ppre-commit profile rewrites files: license headers, OpenRewrite recipes), the resulting changes had to be committed by the orchestrator, and the commit verb failed because the changed files were not staged in the index.

Observation: a hand-back that runs a file-rewriting gate leaves a dirty tree that no leaf owns. The commit path assumed a staged index.

Candidate rule (for the orchestrator to classify): after a handed-back -Ppre-commit run, stage the rewritten paths before calling the commit verb, or let the commit verb stage the gate's own outputs. Check whether the commit verb is meant to stage on its own.

Context: plan plan-20-leftover-fixes-2.
