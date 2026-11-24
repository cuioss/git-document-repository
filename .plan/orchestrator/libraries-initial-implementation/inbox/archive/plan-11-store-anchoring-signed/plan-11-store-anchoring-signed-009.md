envelope_version=1
sender_type=plan
sender_id=plan-11-store-anchoring-signed
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-23T22:35:50Z

# Candidate lesson: Skip finalize steps whose head_at_completion equals HEAD on loop-back replay

component=plan-marshall:phase-6-finalize category=improvement confidence=high source=plan-retrospective

## Context

PLAN-11 (multi_module feature) used 7.4M tokens, about 3x the 2.5M error anchor for multi_module+feature, and 183 minutes of worked time against the 180-minute error bar. 6-finalize alone took 4.63M tokens (63% of the total). Three finalize loop-backs re-ran the same steps: finalize-step-simplify fired 7 times, pre-push-quality-gate, ci-verify and sonar-roundtrip 5 times each, and automatic-review 6 times. The last two simplify rounds reviewed one-line test deltas and returned 0 edits.

## Root cause

On loop-back replay every finalize step is re-dispatched whatever changed since its last firing. A step whose recorded head_at_completion already equals the current HEAD, or whose delta touches no file it reviews, still pays a full dispatch.

## Proposed action

On replay, compare each step's head_at_completion with HEAD and the diff in between. Skip (and record as a fact) a step whose input is unchanged; let finalize-step-simplify short-circuit when the delta touches no main-code file.

## Evidence

- aspect: plan_efficiency - totals.tokens=7406332, 6-finalize=4631964, max_phase_token_share=0.63
- aspect: llm_to_script_opportunities - simplify fired 7 times, last 2 rounds on one-line test deltas
- aspect: chat_history_analysis - 4 finalize loop-backs (PR reopen, 2 triage rounds, 1 inline Sonar fix)
