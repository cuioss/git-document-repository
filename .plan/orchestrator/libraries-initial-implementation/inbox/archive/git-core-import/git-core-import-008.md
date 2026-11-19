envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:49:27Z

# Stop leaves falling back to Bash grep and python edits without Grep

component=plan-marshall:execution-context category=anti-pattern source=plan-retrospective plan=git-core-import

## Context

In plan git-core-import two dispatched leaves ran without `Grep`/`Glob`. The finalize unified-triage leaf (producer
finalize-feedback, round 1) did its read-only lookups with Bash `ls` and `grep` and did not consult the architecture
inventory. The execute envelope-4 leaf renamed `onlyCredentials` to `guardedTransport` in three places of
`JGitRepositoryOperations.java` with a Bash python one-liner instead of the Edit tool. Both said so in their hand-back.
A later triage leaf in the same plan did it right (`architecture search --content`, then Read).

## Root cause

The envelope states the fallback (architecture search, then Read; Edit for edits), but nothing stops a leaf at the
moment it reaches for Bash. The rule is only read as prose, and a leaf under time pressure takes the shell.

## Proposed action

Make the fallback mechanical: have the Bash pre-tool hook reject `grep`/`ls`/`find` and inline `python -c` file
rewrites inside dispatched leaves with a message naming `architecture search --content` / `architecture find` and Edit.
Let the retrospective flag any hand-back that admits such a fallback.

## Evidence

- aspect: chat_history_analysis: triage hand-back "I used `ls` and `grep` via Bash for read-only lookups"; envelope 4
  hand-back "done with a python one-liner through Bash instead of the Edit tool"
- aspect: permission_prompt_analysis: no prompt fired, so nothing else surfaced the bypass
