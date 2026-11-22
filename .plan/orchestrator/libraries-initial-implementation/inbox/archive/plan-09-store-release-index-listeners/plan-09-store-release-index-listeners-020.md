envelope_version=1
sender_type=plan
sender_id=plan-09-store-release-index-listeners
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-22T11:53:03Z

component=git-store
category=bug

# Release trees wrote every changed file as 100644, losing the executable bit; the index scanned a collection once per changed document

Source: review-bot finding b186a7 (CodeRabbit review body, PR #52). The file-mode point was fixed by TASK-10 and the nitpick by TASK-13.

## Defect 1: file mode

`TreeEdit.Put` carried only a path and content, and `InCoreWriter` wrote every `Put` as a regular file (`100644`). When a merge added or changed an executable file (`100755`), the release commit recorded `100644`, so the release tree silently differed from the merged source tree. The fix extended `TreeEdit` and `InCoreWriter` in `git-core`, which the plan spec had declared read-only (see the separate candidate on the read-only guard).

## Defect 2: performance nitpick

An incremental index refresh enumerated the full collection inside the loop over changed documents. 500 changed documents meant 500 scans.

## Rules

- A test of a tree-producing operation must compare file modes as well as file bytes.
- Group per-item work by its container, and enumerate each container once per refresh.
