envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:03:34Z

# Candidate lesson: a new test-scope dependency was not reflected in the GDR-GEN-1 dependency list (Q-Gate c5341a)

Source: Q-Gate finding c5341a, phase 3-outline, scope_criterion_validator, under_coverage, severity warning, resolution taken_into_account. File: doc/specification/general.adoc.

Observation: D4 added org.easymock:easymock at test scope to git-core/pom.xml (owner-approved). doc/specification/general.adoc GDR-GEN-1 lists the declared dependencies of git-core including its test scope, in prose and in a table row. D4's change for general.adoc named only the GDR-GEN-3 readFile entry, so the list and the POM would have diverged.

Resolution in run: D4 records EasyMock in the git-core test scope of GDR-GEN-1, prose list and table row.

Candidate rule (project-specific): any POM dependency change in this repository, test scope included, is paired with the GDR-GEN-1 prose list and table row in doc/specification/general.adoc.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
