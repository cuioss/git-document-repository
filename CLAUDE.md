# CLAUDE.md

@AGENTS.md

Claude Code specifics, in addition to the shared guidance above.

- The release runbook is the project skill `/release` (`.claude/skills/release/SKILL.md`). Use it for every
  release; do not improvise the steps.
- Plan before changing the module structure or adding a module: the order and the open decisions are in
  `doc/git-document-persistence/extraction-from-pm-mcp-core.adoc` and `doc/git-document-persistence/open-points.adoc`.
- Verify claims about JGit APIs against its documentation or source before relying on them; the study marks
  several as _(unverified)_.
- Ask before adding any dependency and before touching `.github/project.yml` (see _Releases_ in `AGENTS.md`).
