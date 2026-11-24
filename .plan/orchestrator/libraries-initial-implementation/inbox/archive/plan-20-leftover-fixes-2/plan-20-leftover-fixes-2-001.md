envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:03:18Z

# Candidate lesson: outline D1 omitted the cross-document outcome sync (Q-Gate bd697b)

Source: Q-Gate finding bd697b, phase 3-outline, scope_criterion_validator, under_coverage, severity warning, resolution taken_into_account.

Observation: the request carried a standing constraint (a changed outcome of a public operation is updated in the Javadoc, the specification table, doc/specification/general.adoc and doc/clients/pm-mcp-core.adoc in the same commit), and the outline Summary repeated it. Deliverable 1 still changed outcomes of commit, worktreeStatus, worktreeRemove and worktreeAdd without listing general.adoc (GDR-GEN-3 row FAILED) or pm-mcp-core.adoc (Concurrent additions, Commit) in its affected files.

Resolution in run: D1 was extended with both files and the change per file.

Pattern note: four of the five outline Q-Gate findings of this plan (bd697b, 16006b, c5341a, c1c7d4) are the same shape: a standing cross-document constraint stated once in the request, not applied per deliverable to the affected-files list. Candidate rule: when a request states a standing "update X, Y, Z in the same commit" constraint, the outline author checks each deliverable against every named document before the Q-Gate runs.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
