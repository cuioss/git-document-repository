envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:50:22Z

# Track the four git-core hardening residuals in a later epic plan

component=plan-marshall:plan-orchestrator category=improvement source=plan-retrospective plan=git-core-import

## Context

PR 25 (git-core-import, merged as 2c728bc) shipped with four hardening gaps that are documented but not closed. No
later plan spec in epic libraries-initial-implementation names them yet:

1. TASK-19 (CodeRabbit 8f6ca2): JGit 7.8.0 has no per-repository config source. Unless an application installs the
   opt-in `RefusingFS.systemReader(SystemReader)` for its JVM, JGit can still run `git` once per JVM to find the
   system config. Documented in the Javadoc and GDR-CORE-1.
2. TASK-20 (CodeRabbit c65ce5): the redirect guard is tested at the transport-factory level with a real
   `TransportHttp`, but there is no end-to-end HTTPS redirect test through `fetch` or `fetchCommit`. A local HTTPS
   server needs a certificate that cannot be made without a new dependency or an external tool.
3. Refusal of a remote-side (ReceivePack) hook is not tested; only the pushing repository's `pre-push` hook is. It is
   unconfirmed whether JGit's ReceivePack runs native hooks at all.
4. CodeRabbit 7e5661 (`http.extraHeader` / `http.cookieFile` from user or system config reaching the remote) was
   declined with a reason (operator-approved), but the triage did not verify in JGit's source that these settings
   apply.

## Root cause

Review-driven fixes ran into JGit's process-wide state and into test-infrastructure limits. The plan correctly
recorded residuals instead of over-reaching, but residuals recorded only in Javadoc, the spec and the PR stay
invisible to the epic's plan sequence.

## Proposed action

Add these four items to a later git-core-touching plan spec (or a dedicated hardening follow-up): verify items 3 and 4
against the JGit 7.8.0 sources, decide whether to approve a test-scope dependency for an HTTPS test server (item 2;
it needs the owner's approval per AGENTS.md), and decide whether `RefusingFS.systemReader` should become the documented
default for embedding applications (item 1).

## Evidence

- aspect: request_result_alignment: review_driven_expansion.residuals_recorded (4)
- aspect: chat_history_analysis: envelope-4 hand-back decisions 1 and 2; envelope-2 "remote-side hook case is not
  tested"; triage round 2 "I did not verify in JGit's source that http.extraHeader and http.cookieFile are applied"
