envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:54Z

# Candidate lesson: per-item work queued for array items without a required-field sub-schema

- Source signal: review-bot finding 5a0a03 (CodeRabbit, PR #81, git-forms/.../MandatoryFields.java:203), resolution fixed (5487c45)
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: project git-forms (MandatoryFields, explicit-stack traversals)

## Observation

After converting the required-field check to an explicit stack, `collectItems` still queued one `Call`, JSON Pointer and identity
set per array item even when the item sub-schema was `null` (schema `{"properties":{"rows":{"type":"array"}}}`). A million-item
array created a million sets that `collect` discarded immediately.

## Possible rule

When replacing recursion by an explicit work stack over untrusted data, filter at push time, not at pop time: push only frames
that can do work (here: sub-schemas that are mappings), keeping the original order. Memory then scales with the schema, not
with the data size.
