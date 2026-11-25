envelope_version=1
sender_type=plan
sender_id=plan-22-store-hardening
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-25T08:07:03Z

Candidate lesson (script failure: plan-marshall:manage-architecture:architecture, 4 argparse rejections)

What happened: The architecture calls were rejected at argparse (exit_code=2) four times. The logged details are:
- 11:54:30: `architecture.py --plan-id plan-22-store-hardening search --content --literal --pattern escalate_ask` (the detail shows only the command; it names no flag).
- 20:18:16: "unrecognized arguments: false".
- 07:10:12: `search --content --pattern 'insideCallback|CLOSE_IN_CALLBACK|String REENTERED'` (again only the command is shown).
- 07:21:56: "unrecognized arguments: --pre .plan/local/plans/plan-22-store-hardening/build-results/git-store/maven-2026-11-25-0..." (truncated).

Evidence: [ERROR] script_failure lines a4033c, af1271, d00a82 and e1c567 in the work log. The two `search --content` details carry no argparse message, so their exact cause is not recorded. The other two show a stray value token ("false") and an undeclared flag `--pre` with a build-results path.

Suggested rule: Check `architecture search --help` before using --literal or a regex alternation. Pass boolean options as bare flags, never as `--flag false`. Do not pass build-result paths to architecture; it takes no `--pre`. The executor should also log the argparse message, not only the command line, for search rejections.
