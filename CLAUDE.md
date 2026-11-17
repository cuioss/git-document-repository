# CLAUDE.md

@AGENTS.md

Claude Code specifics, in addition to the shared guidance above.

- The release runbook is the project skill `/release` (`.claude/skills/release/SKILL.md`). Use it for every
  release; do not improvise the steps.
- Plan before changing the module structure or adding a module: the order and the open decisions are in
  `doc/concepts/module-structure.adoc`, the decisions in `doc/adr/`, and the open questions at the end of each concept.
- Verify claims about JGit APIs against its documentation or source before relying on them, and name the JGit
  version checked.
- Ask before adding any dependency and before touching `.github/project.yml` (see _Releases_ in `AGENTS.md`).

## Temporary Files

Use `.plan/temp/` for ALL temporary and generated files (covered by `Edit(.plan/**)` permission — avoids permission prompts).

## Tool Usage

- Use proper tools (Edit, Read, Write) instead of shell commands (echo, cat)
- Never use Bash for file operations (find, grep, cat, ls) — use Glob, Read, Grep tools instead

## Development Notes

### Build Commands

- Never hard-code build tool invocations; run builds through these canonical commands:
- Compile: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "compile"`
- Quality gate: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Ppre-commit"`
- Full verify: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`
- Coverage: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pcoverage"`
- Use a Bash timeout of 600000ms for builds.
- Analyze each build's TOON result: `status`, `errors[N]{file,line,message,category}`, `log_file`.
