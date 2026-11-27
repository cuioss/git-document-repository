envelope_version=1
sender_type=plan
sender_id=plan-14-forms-deadlines-links-numbers
epic=libraries-initial-implementation
kind=candidate-lesson
created=2026-11-27T19:55:27Z

# Candidate lesson: pre-commit formatter rewrite left uncommitted after the final quality sweep

- Source signal: Q-Gate finding 6e2bc0 (phase 5-execute, lint-issue, warning), resolution fixed
- Plan: plan-14-forms-deadlines-links-numbers
- Component hint: plan-marshall:phase-5-execute (final quality sweep / handback commit)

## Observation

The final quality sweep ran `verify -Ppre-commit`, which applies the configured OpenRewrite/formatter recipes in place.
It inserted a missing space after a comma in a constructor parameter list in
`git-forms/src/main/java/de/cuioss/git/document/forms/TransitionStep.java:116` (`Transition transition,String initialState`).
The rewrite was left as an uncommitted change in the worktree and had to be committed separately as d8516753 (handback commit).

## Possible rule

In a project whose quality gate is a rewriting profile (`-Ppre-commit` rewrites sources), every quality-gate run must be followed
by a dirty-tree check and a commit of the resulting diff before the phase hands back. Alternatively, the per-deliverable
commit should run after the rewriting gate, not before it, so the formatter never produces a diff after the last commit.
