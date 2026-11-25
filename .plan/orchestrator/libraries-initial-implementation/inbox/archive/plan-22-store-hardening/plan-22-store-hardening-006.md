envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:51Z

Candidate lesson (PR #76 review, recurring defect class: re-entry from callbacks and lock holding)

What happened: Over successive bot rounds, each round found one more entry point that misbehaved when called from inside a callback that holds a lock of the clone, or that handled the lock unsafely:
- StoreWriter.close from inside a save step, listener, contributor or anchor-provider call (924dc6, TASK-15/16).
- anchor() and a due check called inside a callback (230fcc, TASK-19/20).
- A stale check run outside the index lock (TASK-17/18).
- A recursive index retry (dd68d7, TASK-19/20).
- Two indexes of one writer that could deadlock through a nested no-move callback, fixed by one shared lock (c5f522, TASK-21/22).
- Two more fixes in TASK-23/24 (7e7455, 4efeb8).

Evidence: pr-comment findings resolved as fixed, and the work-log titles of TASK-15 to TASK-24. The fixes added RepositoryGuard and StoreEvents changes plus the test helper ThreadStates.

Suggested rule: For a component with callbacks, list every public entry point and decide its behaviour when re-entered while a lock is held, all at once. Enforce the decision through one guard seam, and check it with a single parametrised test that calls every entry point from every callback kind. Do not fix the entry points one at a time.
