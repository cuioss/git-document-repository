# AGENTS.md

Guidance for AI coding agents working in this repository. `CLAUDE.md` imports this file and adds the
Claude Code specifics.

## Project

`git-document-repository` holds base libraries for _Git as the Persistence of Versioned Documents_: a
server-side Git repository (reached through JGit, in memory, without working trees) as the persistence of an
application in which users maintain versioned documents. The documentation index is
[`doc/README.adoc`](doc/README.adoc): concepts in `doc/concepts/`, decisions in `doc/adr/`, and the specification in
`doc/specification/`, one document per module, which states the requirements (prefix `GDR`, numbered without gaps)
together with their design.
Read the concepts before changing the architecture. Every statement about an external product, standard or law cites
the source it was checked against; do not add unverified statements.

The application built on these libraries, TrailHand, lives in its own repository,
[`cuioss/TrailHand`](https://github.com/cuioss/TrailHand), together with its documentation (it was `doc/app/` here until
2026-11-20). Do not document the application here: this repository's documentation names it only in the index
[`doc/README.adoc`](doc/README.adoc), and the application's documentation links into this one only to concepts and
specifications, so keep their addresses stable.

The repository is a multi-module Maven reactor (group `de.cuioss`, parent `cui-java-parent`): the modules are
released together and consumed separately.

| Module | Purpose | Depends on |
|---|---|---|
| `git-core` | Hardened repositories, remote policy, commit and reference identity, results, transport, reading, cache repository, in-core writing, compare-and-swap, in-core merge, notes; linked working trees, commit from a working tree, working-tree status (package `worktrees`) | - |
| `git-testing` | Generators and fixtures: local and in-memory repositories, a local remote | - |
| `git-store` | Repository registry, typed configuration, codec interface, outcome list and result, service interfaces with provider selection; write path: reference layout, drafts, explicit save with lease and per-attempt step, conflicts and change detection, release with tag, draft expiry; read side: in-memory index with contributors, fetch and snapshots, post-commit listeners | `git-core` |
| `git-forms` | Schema-driven documents: outcome list and result, log messages, kinds of documents as configuration, schema validation, data layout, YAML codec | `git-store` |

Planned but not yet created: `git-timestamp`, `git-directory` (see
`doc/concepts/module-structure.adoc` for the order and the dependency graph).

**Current state:** `git-testing` exports `de.cuioss.git.document.testing` with the transport-credential generator
(`GitCredentialGenerator`, `GitGenerators`) and the three repository fixtures (`@LocalRepository`, `@InMemory`,
`@Remote`). `git-core` exports `de.cuioss.git.document.core` with the repository part taken over from
`pm-provider-git` as a plain copy: `RefusingFS` with `CapabilityRefusedException`, `RemotePolicy`, `GitIdentity`,
`GitResult` and `GitOutcome`, `RepositoryOperations` with `JGitRepositoryOperations` (open, init, `cloneIfMissing`,
log, fetch, push, cache repository) and the `LogRecord` holder `GitCoreLogMessages`, and with in-core writing and
compare-and-swap: `InCoreWriter` with `TreeEdit`, `CommitMetadata` and `CommitSigning`, and `ReferenceUpdater` with
`TreeOperation` (compare-and-swap and compare-and-delete of a reference), and with in-core merge, reading and notes:
`InCoreMerger` with the sealed `MergeResult` (the merged tree, or the conflicting paths as data), `RepositoryReader`
(snapshot, path history, changed paths) and `GitNotes` (read and write a note under a caller-named notes reference,
by compare-and-swap). `git-core` also exports `de.cuioss.git.document.core.worktrees` with the worktree part taken
over from `pm-provider-git`: `WorktreeOperations` with `JGitWorktreeOperations` (add, list and remove linked working
trees, commit from a working tree, working-tree status `WorktreeStatus`, release of a branch reservation left by an
ended process) and the package-private `LinkedWorktrees`; its log messages are declared in `GitCoreLogMessages`, the
single `LogRecord` holder of `git-core`. The tests of both packages run on the class path against the `git-testing`
fixtures. `git-store` exports `de.cuioss.git.document.store` with the foundation of the store: the
closed outcome list `StoreOutcome` and the result `StoreResult` (whose outcome is a `StoreOutcome` or a non-success
Git outcome passed on as `StoreResult.PassedOn`), the codec interface `Codec`, the repository registry
`RepositoryRegistry` with `StoreRepository`, the typed configuration `StoreConfiguration` (built by
`StoreConfiguration.from` from an already-parsed nested map, over the package-private reader `ConfigurationTree`; its
sections include `commit`, the record `Commit` with `commit.email`), the
service interfaces `SigningKeySource`, `AnchorProvider` and `PersonDirectory` with `PersonEntry` and `Membership`, the
provider selection `StoreProviders` through `ServiceLoader`, and the `LogRecord` holder `GitStoreLogMessages`; and the
write path: `StoreWriter` (drafts, listing, explicit save with lease and the optional per-attempt `SaveStep`,
`release` of a document with its tag returning `Release`, draft expiry with garbage collection, `fetch` and
`fetchPeriodically` on the caller's executor returning a `FetchSchedule`, and `subscribe` of a per-branch `CommitListener`) with
`StoreReferences`, `DocumentCollection`, `DocumentKey`, `Ulid`, `Draft`, `SaveResult`, `TechnicalUser` and `Author`,
and the in-memory index `StoreIndex` (built from `main` and every working and collection branch, refreshed after
every fetch, save and release, with `snapshot` of a tag or commit) over the consumer's `IndexContributor` per
collection, over the package-private `DraftReferences`, `ExplicitSave`, `DocumentRelease`, `DocumentPaths`,
`DraftExpiry`, `StoreEvents`, `ListenerDispatch`, `CommitIdentities` and `RepositoryGuard`; the commits it writes are
unsigned and carry no trailers until GDR-STORE-12/13. JOL (`org.openjdk.jol:jol-core`) is a test-scope dependency of
`git-store` only, for `IndexFootprintTest` (GDR-NFR-1). Its tests run on the class path against
the `git-testing` fixtures. `git-forms` exports `de.cuioss.git.document.forms` with the closed outcome list
`FormsOutcome`, the result `FormsResult` (whose outcome is a `FormsOutcome` or a non-success store outcome passed on as
`FormsResult.PassedOn`) and the `LogRecord` holder `GitFormsLogMessages`, and with the kinds of documents as
configuration: `KindConfiguration` (loads `kinds.yaml` from a directory or a commit of a configuration repository) with
`Kind` and `Numbering`, over the package-private `YamlReader` (YAML within the JSON data model, on
`org.snakeyaml:snakeyaml-engine`), and with schema validation, data layout and codec: `FormSchema` (a JSON Schema of
Draft 2020-12, its declaration order and the split keyword `gdr-split`), `SchemaValidator` (on `dev.harrel:json-schema`,
`required` checked separately) with the sealed `ValidationResult` and `Violation`, `DocumentLayout`, `YamlDocumentCodec`
and `CounterCodec`, over the package-private `DataNode` (the validator's `JsonNode` over the data model) and
`YamlWriter` (the canonical emitter); its tests run on the class path as well.
Add an `exports` directive together with the first
public class of a package: javac rejects exporting a package that contains only `package-info.java`.

The build guards GDR-GEN-1 (`doc/specification/general.adoc`) with the `maven-enforcer-plugin` that `cui-parent-pom`
already binds: the execution `module-layering` of the root `pom.xml` bans every reactor dependency outside the module
graph (`git-store` appends the edge `git-core`, `git-forms` the edge `git-store`), and the execution `consumer-isolation` of
`git-core/pom.xml` bans every compile or runtime artifact outside the closure a consumer of `git-core` alone receives.
`DomainTermAbsenceTest` in each module (`git-core`, `git-testing`, `git-store` and `git-forms`) asserts that no public type carries
a domain term. `git-core` ships native-image metadata (`reflect-config.json`, `resource-config.json` under
`META-INF/native-image/de.cuioss/git-core/`, checked by `NativeImageMetadataTest`) for the JGit translation bundle it
reaches, its worktrees package included; `git-store` ships none and relies on that of `git-core`; `git-forms` ships
`resource-config.json` only, for the bundle and the meta-schema its JSON Schema validator loads (GDR-NFR-4).

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

These follow the conventions of the foundation code that moves into this repository from its first client
(see [`doc/clients/pm-mcp-core.adoc`](doc/clients/pm-mcp-core.adoc)), so it can move without rewriting:

- Java modules (JPMS): every module has a `module-info.java`; the automatic module name in the pom is
  `de.cuioss.git.document.<suffix>`.
- Null safety with JSpecify: `@NullMarked` per package, `@Nullable` where needed.
- Expected outcomes are returned as results, not thrown as exceptions.
- Logging with `CuiLogger` and a `LogRecord` holder per module.
- Tests: JUnit 5, `cui-test-generator` for test data, EasyMock for test doubles.
- Third-party dependencies stay out of the lower modules: a consumer of `git-core` alone must pull none of
  the dependencies of `git-store`, `git-forms` or `git-timestamp`.
- **Never add a dependency without the owner's approval**, JGit included.
- A new module adds its allowed reactor edges to the `module-layering` rule: one `<include>` per edge of the module
  graph, in its own POM (see `git-store/pom.xml`).
- A new compile dependency of `git-core` is added to the `consumer-isolation` rule of `git-core/pom.xml` only after the
  owner's approval; until then the build fails.
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
6. Do not enable auto-merge. A plan-marshall run is authorised to merge its own pull request as part of landing it
   (the `branch-cleanup` finalize step), once the checks are green and every review comment is handled. This never
   covers a pull request that changes `release.current-version` in `.github/project.yml`: merging it is a Maven
   Central release (see _Releases_) and goes through the runbook only. Outside a plan-marshall run, do not merge
   unless the user says so.
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
- `maven-build.enable-snapshot-deploy` is `true`: every push to `main` deploys the SNAPSHOT modules to the
  Sonatype snapshot repository. That is not a release.
- There is no Maven site and no GitHub Pages (`pages.deploy-at-release: false`); do not add either back
  without the owner's decision.
- Any PR that touches `.github/project.yml` starts the Release workflow on merge. The guard must skip it:
  check the run afterwards.

## Temporary Files

Use `.plan/temp/` for all temporary and generated files.
