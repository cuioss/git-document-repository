envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:30Z

# Candidate lesson: git-forms has no architecture-resolved skills (skills_by_profile undeclared-empty)

Signal: Q-Gate, 4-plan, two findings (a061c4 implementation, 5af2f5 module_testing). Both were accepted by the operator at 07:52.

## What happened

phase-4-plan warned that module `git-forms` has an UNRESOLVED (undeclared-empty) `skills_by_profile.implementation` and `.module_testing`. Tasks got their skills from persona augmentation only. The operator accepted both findings with "persona-derived skills are sufficient for this plan. No config change." Also observed: the persona resolver reported `module_status=unreadable` for module `documentation` (07:44:15).

## Candidate rule

The finding repeats on every git-forms plan until the module's enrichment is settled. The epic should decide once: either run architecture enrichment for git-forms (and the documentation module), or declare the profiles `minimal: true` on purpose. Accepting per plan moves the same decision to every later plan.

Scope: project architecture enrichment (git-forms, documentation). This is a project configuration fact, not a code defect.
