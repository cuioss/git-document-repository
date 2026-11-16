# AGENTS.md

Guidance for AI coding agents working in this repository. `CLAUDE.md` imports this file and adds the
Claude Code specifics.

## Project

`git-document-repository` holds base libraries for _Git as the Persistence of Versioned Documents_: a
server-side Git repository (reached through JGit, in memory, without working trees) as the persistence of an
application in which users maintain versioned documents. The design is in the study
[`doc/git-document-persistence/README.adoc`](doc/git-document-persistence/README.adoc); read it before
changing the architecture. Statements marked _(unverified)_ in the study are not checked against a source.

The repository is a multi-module Maven reactor (group `de.cuioss`, parent `cui-java-parent`): the modules are
released together and consumed separately.

| Module | Purpose | Depends on |
|---|---|---|
| `git-core` | Hardened repositories, remote policy, identity, results, transport, reading, cache repository, in-core writing | - |
| `git-worktrees` | Linked working trees, commit from a working tree, working-tree status | `git-core` |
| `git-testing` | Generators and fixtures: local and in-memory repositories, a local remote | - |

Planned but not yet created: `git-store`, `git-forms`, `git-crypto`, `git-signing`, `git-timestamp`,
`git-directory` (see `doc/git-document-persistence/extraction-from-pm-mcp-core.adoc` for the order and the
dependency graph).

**Current state:** the modules are scaffolds. Each has a `pom.xml`, a `module-info.java` and a `@NullMarked`
package, and no classes yet. The `exports` directive is missing on purpose: javac rejects exporting a package
that contains only `package-info.java`. Add it together with the first public class of a package.

## Build

```bash
./mvnw clean verify                       # build and test everything
./mvnw test -Dtest=ClassName              # one test class
./mvnw test -Dtest=ClassName#methodName   # one test method
./mvnw -pl git-core -am verify            # one module with what it depends on

# Pre-commit auto-fix: license headers and every configured OpenRewrite recipe (modernisation, not only
# formatting). Review every resulting diff and commit it.
./mvnw -Ppre-commit clean verify -DskipTests
```

Always build and test through Maven and JUnit; do not call `javac` directly and do not add ad-hoc debug or
verification classes outside JUnit. CI builds on Java 21 and 25; the compile target is Java 21.

## Conventions

These follow the conventions of the code that moves into this repository (`pm-provider-git` of `pm-mcp-core`),
so it can move without rewriting:

- Java modules (JPMS): every module has a `module-info.java`; the automatic module name in the pom is
  `de.cuioss.git.document.<suffix>`.
- Null safety with JSpecify: `@NullMarked` per package, `@Nullable` where needed.
- Expected outcomes are returned as results, not thrown as exceptions.
- Logging with `CuiLogger` and a `LogRecord` holder per module.
- Tests: JUnit 5, `cui-test-generator` for test data, EasyMock for test doubles.
- Third-party dependencies stay out of the lower modules: a consumer of `git-core` alone must pull none of
  the dependencies of `git-forms`, `git-crypto` or `git-timestamp`.
- **Never add a dependency without the owner's approval**, JGit included.
- Documentation is AsciiDoc; Javadoc on every public type and method.
- Every source file carries the license header; `-Ppre-commit` maintains it. `inceptionYear` in the root pom
  must stay set, otherwise every run restamps all headers.

## Git Workflow

`main` is protected: direct pushes are never allowed, and the required check is `build / conclusion`.

1. Create a branch with a CI-recognised prefix: `git checkout -b feature/<name>` (or `fix/`, `chore/`).
2. Commit, then `git push -u origin <branch>`.
3. Open the PR: `gh pr create --repo cuioss/git-document-repository --head <branch> --base main --title "<title>" --body "<body>"`.
   Add the label `skip-bot-review` only when the review bots are not wanted.
4. Wait for the checks: `gh pr checks --watch`.
5. Handle every review comment: fetch with `gh api repos/cuioss/git-document-repository/pulls/<n>/comments`.
   - Clearly valid and fixable: fix, commit, push, reply with the fix, resolve.
   - Disagree or out of scope: reply with the reason, resolve.
   - Not 100% sure: ask the user first.
   - Every comment gets a reply and is resolved.
6. Do not enable auto-merge and do not merge unless the user says so.
7. Afterwards: `git checkout main && git pull`.

## Releases

Merging a change of `release.current-version` in `.github/project.yml` **is** a Maven Central release: the
central version-changed guard of `reusable-maven-release.yml` publishes whenever the value differs from the
merge commit's first parent and no tag exists. Maven Central releases cannot be withdrawn.

- Never change `release.current-version` or `release.next-version` in an ordinary PR.
- Never dispatch the Release workflow outside the runbook: `workflow_dispatch` publishes unconditionally.
- Releases go through the runbook `.claude/skills/release/SKILL.md`. The first release (`0.1.0`) is a
  deliberate dispatch from `main`, only once the modules contain code.
- Do not rewrite the `cuioss/cui-java-module-template` literal in `.github/workflows/release.yml`; it only
  excludes the template repository itself.
- `maven-build.enable-snapshot-deploy` is `false` until a module contains code. Set it to `true` then.
- There is no Maven site and no GitHub Pages (`pages.deploy-at-release: false`); do not add either back
  without the owner's decision.
- Any PR that touches `.github/project.yml` starts the Release workflow on merge. The guard must skip it:
  check the run afterwards.

## Temporary Files

Use `.plan/temp/` for all temporary and generated files.
