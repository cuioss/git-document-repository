envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:06:53Z

Candidate lesson (PR #76 review, defect class: one value read twice or cached without its full key)

What happened: Several fixes concerned state that was observed more than once, or cached without the input it depends on:
- The AnchorVerification reachability cache was keyed by listed object and reference name but not by the observed tip. If a reference moved between two checkpoints, REFERENCE_UNREACHABLE was missed. The Javadoc also claimed that references do not change during the run (2ef996, TASK-9).
- Provider selection read the provider id more than once and now reads it once per selection (512ae0, TASK-17/18).

Evidence: pr-comment findings resolved as fixed. The bot comment 2ef996 includes the suggested cache-key diff.

Suggested rule: A cache key must include every input the cached result depends on. A value from third-party code (a provider id, a reference tip) is read once per decision and passed on, never re-read. A Javadoc that states an invariant the code does not enforce counts as a defect.
