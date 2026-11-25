envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:48Z

Candidate lesson (Q-Gate 4-plan, findings 419aee and ac9876, same cause)

What happened: Module git-store has undeclared, empty skills_by_profile entries for both implementation and module_testing. Phase 4 therefore created all tasks with only the persona-resolver skills (no architecture defaults or optionals). The two findings are one cause reported once per profile.

Evidence: qgate findings 419aee (git-store.implementation) and ac9876 (git-store.module_testing), both accepted. The resolution says all 8 tasks ran with the persona-resolver Java skills plus three judgment-added skills and completed green, and that architecture enrichment is a project-configuration concern outside PLAN-22.

Suggested rule: Enrich skills_by_profile for git-store (and check the sibling modules), or declare the profiles minimal: true deliberately, so later plans of this epic stop re-raising the same two findings.
