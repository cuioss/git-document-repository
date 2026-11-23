envelope_version=1
sender_type=plan
sender_id=plan-10-store-signing-trailers-verify
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T14:36:29Z

# Candidate lesson: sibling epic plans conflict on the shared documentation index and general specification

Source: boundary cause baseline_drift (signal-scan boundary_cause_counts.baseline_drift=1). At 2026-11-23T12:09:03Z phase-5-execute found that origin/main had advanced by bdbb5e4 (PR #59, git-forms hardening). The merge-tree probe found 2 conflicting files: doc/README.adoc and doc/specification/general.adoc. The orchestrator re-dispatched phase-2-refine, the conflict was reconciled (baseline_drift_reconcile done), and no task was lost. One envelope was spent.

Component: epic libraries-initial-implementation (plan sequencing); doc/README.adoc and doc/specification/general.adoc

## Candidate observation for the orchestrator

Every plan of this epic updates the implementation-state lines in doc/README.adoc and doc/specification/general.adoc (and the "Current state" paragraph of AGENTS.md). Plans that run at the same time therefore conflict on these files whatever their module. Options: treat these files as a declared shared surface in the epic's surface partition, so overlapping plans are serialised or the edit is deferred to a landing step; or keep one line per module so that concurrent edits touch different lines.
