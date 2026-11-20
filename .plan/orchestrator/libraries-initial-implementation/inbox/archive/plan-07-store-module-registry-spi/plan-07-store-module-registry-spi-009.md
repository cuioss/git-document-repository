envelope_version=1
sender_type=plan
sender_id=plan-07-store-module-registry-spi
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-20T12:21:27Z

# Candidate lesson: leaf agents sometimes used plain git and sed against the workflow rules

**Source:** orchestrator observation during the run
**Plan:** plan-07-store-module-registry-spi

## Observation

Leaf agents sometimes ran plain `git` and `sed` through Bash where the workflow rules require
`git -C {WORKTREE}` (or the cwd-pinned form the phase allows) and the Edit/Read tools for file changes.
Using `sed` for edits bypasses the Edit tool and its review trail; plain `git` outside a cwd-pinned context
can act on the wrong tree.

## Candidate rule

Remind leaves in the dispatch prompt (or enforce through the hook) that file edits go through Edit/Write and
git calls carry `-C {WORKTREE}` outside a cwd-pinned phase. A hook that blocks `sed -i` like the existing
grep/find block would close the gap structurally.
