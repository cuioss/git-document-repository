envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:03:26Z

# Candidate lesson: outline D2 left stale wording in sibling documents (Q-Gate 16006b)

Source: Q-Gate finding 16006b, phase 3-outline, scope_criterion_validator, under_coverage, severity warning, resolution taken_into_account.

Observation: D2 documented the bare-root refusal (PATH_REFUSED) and corrected a stale reservation sentence in the Javadoc and git-core.adoc only. doc/specification/general.adoc (GDR-GEN-3 row PATH_REFUSED) and doc/clients/pm-mcp-core.adoc (Concurrent additions) carried the same stale wording and were not in the affected files, against the Done-when clause "no document lists an outcome the code does not return".

Resolution in run: D2 was extended with both files and the change per file.

Candidate rule: when a deliverable corrects a sentence, sweep the other documents for the same sentence (content search) and list every hit, rather than fixing only the file where the defect was first seen. Same shape as bd697b, c5341a, c1c7d4.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
