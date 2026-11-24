envelope_version=1
sender_type=plan
sender_id=plan-20-leftover-fixes-2
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T07:03:50Z

# Candidate lesson: a new build guard was added without updating the guard list and AGENTS.md (Q-Gate c1c7d4)

Source: Q-Gate finding c1c7d4, phase 3-outline, scope_criterion_validator, under_coverage, severity warning, resolution taken_into_account.

Observation: D6 added a second enforcer execution (direct-dependency-isolation) to git-core/pom.xml. doc/specification/general.adoc GDR-GEN-1 says "Three checks realise the requirement" and lists them; AGENTS.md describes the GDR-GEN-1 build guards and the consumer-isolation convention. D6 recorded the negative runs and trade-offs but not the new check in the list, and AGENTS.md was not in its affected files.

Resolution in run: D6 adds direct-dependency-isolation as the fourth GDR-GEN-1 check and lists AGENTS.md (guard paragraph and convention naming both rules).

Candidate rule (project-specific): a change to the enforcer executions of this repository updates the GDR-GEN-1 check list (including its count) and the build-guard paragraph and conventions of AGENTS.md.

Context: plan plan-20-leftover-fixes-2, PR 69 (merged f7eda4e).
