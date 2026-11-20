envelope_version=1
sender_type=plan
sender_id=implement-plan-06-build-guards
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T07:17:21Z

# Candidate lesson: review bot flagged hand-mirrored lists of documented sets (accepted as trade-offs)

Source: automatic-review signal for PR #39 (CodeRabbit). No finding was fixed in the run; four pr-comment findings were dispositioned.

## Records

- e7df0a (inline, git-core DomainTermAbsenceTest.java:45) — accepted. The four GDR-GEN-1 domain terms are copied into three module tests; the bot asked for one machine-readable definition. Kept: each module test fails on its own, and a shared definition would need a new public type in the published git-testing module for a four-entry list. Each constant cites GDR-GEN-1.
- 20b1dc (inline, git-worktrees/pom.xml:83) — accepted. The enforcer include for the git-worktrees -> git-core edge mirrors the graph in doc/concepts/module-structure.adoc by hand; generating both from one file would add a build step for a three-edge graph.
- 1d1eac (review body) — taken_into_account. Unverified claim that Enforcer 3.6.3 bannedDependencies with searchTransitive drops optional direct dependencies before matching, so an optional compile dependency could bypass consumer-isolation. Not verified against the Enforcer source; no optional dependency exists in the reactor today.
- 73476f (summary) — taken_into_account.

## Candidate rule

The project path instructions treat a hard-coded list mirroring a set defined elsewhere as a defect unless derived at build/run time. Guards in this epic (domain terms, module edges) knowingly violate that; either record the accepted exception in the specification so later reviews do not re-raise it, or derive the lists. The optional-dependency gap in consumer-isolation is open and unverified and may deserve a check.
