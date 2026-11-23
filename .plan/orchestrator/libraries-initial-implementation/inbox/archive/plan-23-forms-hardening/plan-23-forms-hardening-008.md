envelope_version=1
sender_type=plan
sender_id=plan-23-forms-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T12:11:41Z

# Candidate lesson: git-store ConcurrentWritersTest.tenWritersWithOwnClones is load-dependent flaky

Signal: test failure during pre-push-quality-gate whole-tree run (findings 47f163, 864635, 65bf93; build log maven-2026-11-23-101702; decision log 10:20:25).

## What happened

The whole-tree `verify -Ppre-commit` run went red once in `de.cuioss.git.document.store.ConcurrentWritersTest.tenWritersWithOwnClones(LocalRemote)` (GDR-NFR-2): one of the 10 writers had its lease refused on every attempt. The branch touched nothing under git-store, git-core or git-testing. The class passed on its own on identical code (4/4), and the whole-tree re-run (maven-2026-11-23-102027, 1456 tests) was green. git-store was read-only for this plan, so it was not fixed here.

## Candidate rule

This is a real defect outside this plan: a GDR-NFR-2 concurrency test whose result depends on machine load. Likely cause: under load one writer runs out of lease retries. A git-store plan should make the test deterministic, for example with bounded retries plus a start barrier, or an attempt budget that does not depend on wall-clock contention. Otherwise every whole-tree gate on this repository can go red at random and cost a re-run.

Scope: git-store (ConcurrentWritersTest, ExplicitSave lease retry). Belongs to a git-store plan of this epic.
