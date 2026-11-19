envelope_version=1
sender_type=plan
sender_id=git-core-import
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-18T13:49:51Z

# Give trigger-bot the newest finding SHA without a 188KB findings read

component=plan-marshall:automatic-review category=improvement source=plan-retrospective plan=git-core-import

## Context

Four automatic-review dispatches in plan git-core-import (iterations at HEADs 98ec1a0, a0a1e4f and 19b2ea5 twice)
needed the newest bot finding's commit SHA for `trigger-bot --reviewed-commit-sha`. The only source was the stored
pr-comment findings listing, which had grown to about 188KB with 71 findings. Three dispatches passed an empty value
and one passed a SHA taken from a partial sample. With `re_review_on_loopback: false` this did not change the result,
but the input was not computed as documented, and it would matter on a re-review-on-loopback configuration.

## Root cause

No query returns only the newest finding's SHA, so the leaf has to read and scan the full listing, which is too large
to read whole.

## Proposed action

Add a narrow `manage-findings` query (newest finding by type, returning its commit SHA), or have `trigger-bot` resolve
the SHA itself from the store when the flag is omitted.

## Evidence

- aspect: llm_to_script_opportunities: 4 repetitions, low complexity
- aspect: chat_history_analysis: "The newest finding's SHA was in a 188KB listing"; "came from that sample, not
  necessarily the newest finding"
