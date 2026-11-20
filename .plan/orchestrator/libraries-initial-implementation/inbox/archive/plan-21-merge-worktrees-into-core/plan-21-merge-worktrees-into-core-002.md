envelope_version=1
sender_type=plan
sender_id=plan-21-merge-worktrees-into-core
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T17:46:09Z

## Candidate lesson: a success criterion named a file its verification command never checked

Source plan: plan-21-merge-worktrees-into-core (PLAN-21, PR #46). Phase: 3-outline Q-Gate.

What happened. The success criterion of deliverable 4 read "The AsciiDoc link check over doc/ and over README.adoc reports nothing unresolved". The deliverable's verification command ran only `asciidoc verify-links --directory doc --recursive`, so the root README.adoc, which lies outside doc/, was never checked. The outline Q-Gate scope_criterion_validator caught it as under_coverage (finding e18e3f, severity warning, file README.adoc). It was resolved taken_into_account: TASK-005 verification gained a second call, `asciidoc verify-links --file README.adoc`.

Why it matters. The gate caught it this time. The authoring gap is the general one: a criterion that names several scopes (a directory plus a root-level file) must have a verification command for each named scope. A directory-recursive check does not reach a file at the repository root.

Candidate corrective rule. When the outline writes a success criterion that names more than one scope, it writes one verification call per scope, or narrows the criterion to what the command checks. A root-level file named beside a directory gets its own file-scoped check.

Likely owning component: plan-marshall:phase-3-outline (deliverable verification authoring). The Q-Gate validator worked as intended.
