envelope_version=1
sender_type=plan
sender_id=plan-13-forms-output-workflow-stepup
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-24T16:26:04Z

## Candidate lesson: check the upstream API before a refine decision keeps an upstream module untouched

Signal: retrospective (chat history, decision log, request-result alignment).

What happened: in refine, the operator decided that `git-forms` builds on `git-store` as it is ("no git-store change"), and the outline repeated it as a hard constraint. CodeRabbit's first review round then raised 9 findings. 3 of them could not be fixed inside git-forms under that constraint: the release was not pinned to the saved commit (e57295), state-only staging (ad5ce3), and `ExplicitSave` invoking the step on an empty diff (360e76). They were deferred to the operator in the middle of finalize. The operator then approved a git-store change for e57295 only (a pinned-release overload in `DocumentRelease` / `StoreWriter`), and the other two got forms-side workarounds. This cost one extra operator round and left 5 git-store files outside the declared scope.

Suggested rule: when a refine or outline decision states that an upstream module stays untouched, list the guarantees the new code needs from that module's API and check each one against the shipped code before freezing the constraint. For a write path these are: release or tag pinned to the validated commit, the per-attempt step's behaviour on an empty diff, and state-only staging. A guarantee that is missing becomes an explicit owner question in refine, not a review finding in finalize.

Component hint: plan-marshall:phase-2-refine (constraint questions) and plan-marshall:phase-3-outline (upstream-untouched constraints). Project: git-forms on git-store.
