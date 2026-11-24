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
| `git-store` | Repository registry, typed configuration, codec interface, outcome list and result, service interfaces with provider selection; write path: reference layout, drafts, explicit save with lease and per-attempt step, conflicts and change detection, release with tag, draft expiry; read side: in-memory index with contributors, fetch and snapshots, post-commit listeners; signing and its verification; anchoring: checkpoints, proofs as notes, the provider `signed`, anchor verification | `git-core` |
| `git-forms` | Schema-driven documents: outcome list and result, log messages, kinds of documents as configuration, schema validation, data layout, YAML codec, template and generated output with its check, workflow with its transitions over the store mechanisms, step-up per transition | `git-store` |
| `git-timestamp` | The RFC 3161 anchor provider `rfc3161`: obtaining timestamp tokens from an external authority and verifying them against `timestamp.trust`, on an own DER/CMS reader and writer (optional) | `git-store` |

Planned but not yet created: `git-directory` (see
`doc/concepts/module-structure.adoc` for the order and the dependency graph).

**Current state:** `git-testing` exports `de.cuioss.git.document.testing` with the transport-credential generator
(`GitCredentialGenerator`, `GitGenerators`), the distinct-value generator `DistinctTextGenerator` (through
`GitGenerators.distinctTexts()`, for a value asserted absent from a text or two values that must differ), the three
repository fixtures (`@LocalRepository`, `@InMemory`, `@Remote`) and the host-configuration isolation
`@HermeticGitConfiguration` with `HermeticGitConfigurationExtension` (not for tests that run concurrently in one JVM). `git-core` exports `de.cuioss.git.document.core` with the repository part taken over from
`pm-provider-git` as a plain copy: `RefusingFS` with `CapabilityRefusedException`, `RemotePolicy`, `GitIdentity`,
`GitResult` and `GitOutcome`, `RepositoryOperations` with `JGitRepositoryOperations` (open, init, `cloneIfMissing`,
log, fetch, push, whose leased push that lost a race on the remote reference is `LEASE_REFUSED` and whose permanent
refusal is `REJECTED`, cache repository) and the `LogRecord` holder `GitCoreLogMessages`, and with in-core writing and
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
provider selection `StoreProviders` through `ServiceLoader`, the signing key source `FileSigningKeySource` (id `file`,
an unencrypted OpenSSH Ed25519 key), and the `LogRecord` holder `GitStoreLogMessages`; and the
write path: `StoreWriter` (`open` with the declared collections, a name declared twice refused as `MALFORMED`, and the
active `SigningKeySource`; drafts, listing, explicit save with lease, retried only on a lost lease, and the
optional per-attempt `SaveStep` taking the `IdentityClaims` of the user, whose `SaveStep.Attempt` view reports the
`mode` of a path, a save keeping the file mode of every draft put,
`release` of a document with its tag taking the `IdentityClaims` and returning `Release`, optionally pinned to the commit
id a save returned (with `branchMovedOn` for a
repair of an incomplete release; a tag the remote already holds at the release commit completes it, a gitlink path is
`MALFORMED`, a numbered entry `main` changed since the previous release is `CONFLICT`, a failed push of `main` leaves no
local tag), draft expiry with garbage collection (`REFUSED` when re-entered from a callback holding the shared lock), `fetch` and
`fetchPeriodically` on the caller's executor returning a `FetchSchedule`, and `subscribe` of a per-branch `CommitListener`
registered before its start is read) with
`StoreReferences`, `DocumentCollection`, `DocumentKey`, `Ulid`, `Draft`, `SaveResult`, `TechnicalUser` and `Author`,
and the in-memory index `StoreIndex` (built from `main` and every working and collection branch, refreshed after
every fetch, save and release, each source read in isolation, with `snapshot` of a tag or commit) over the consumer's
`IndexContributor` per collection, over the package-private `StoreClone` (the opened clone, to which `StoreWriter`
delegates, with its events, the listener subscription and the closed flag that `close()` sets under the anchoring lock,
so no due check runs after the close; a close issued inside a callback that holds a lock of the clone, a save step or
an anchor provider call, is refused without waiting, leaves the writer open and logs `GIT_STORE-114`, and an `anchor()`
issued there is refused the same way, as `REFUSED` without a log), `CloneWrites` (drafts, listing, save, release, expiry), `CloneSchedules` (fetch,
periodic fetch, anchoring, the coalescing due-check trigger) and `CloneOpening` (checks the arguments of `open`, builds
the providers and those parts), `DraftReferences`,
`ExplicitSave`, `DocumentRelease`, `DocumentPaths`, `DraftExpiry`, `StoreEvents` (the notification seam and the one
isolation rule of the store's callbacks: a listener, contributor, step or anchor provider isolates a
`RuntimeException` and every `Error` but a `VirtualMachineError`, the two scheduled runners every `Throwable`),
`ListenerDispatch`, `CommitIdentities` and `RepositoryGuard`. Every save and release
commit is signed in the SSH signature format (GDR-STORE-12) through the package-private `SshSignatures` (an own
SSHSIG encoder, no dependency), `SourceSigner` and `CommitSignatures`, and carries the identity trailers of
`IdentityTrailers` (GDR-STORE-13), built from `IdentityClaims` (`IdentityClaims.of` returns a token value that does not
fit a trailer line as `MALFORMED`); draft commits are neither. A `RuntimeException` of a third-party signing key source
is isolated as `REFUSED`. Every blob read of the store is bounded by 16 MiB (`ExplicitSave.BLOB_LIMIT`). The signature
verification (GDR-STORE-14) is `AllowedSigners` (Git's `allowed_signers` format, read bounded: a regular file of at
most 64 KiB and 1024 lines in UTF-8, a malformed file or path a result), `SignatureVerification` (a second `gpgsig`
header is `INVALID`, no commit body retained) and the CI main class `VerifySignatures`.
The anchoring (GDR-STORE-15 to GDR-STORE-18) is `StoreWriter.open` with the selected `StoreProviders`, `anchor()`
(one due check, returning `StoreWriter.Anchoring`; an unchecked failure of its first phase `INCOMPLETE`, a closed
writer `REFUSED`, an empty proof refused) and `anchorPeriodically` on the caller's executor returning an
`AnchorSchedule`, over the package-private `AnchorEngine` (due check under the clone's anchoring lock, a coalesced due
check after every save, release and fetch), `Checkpoints` (checkpoint commits with the file `refs`, never-moved tags
`refs/tags/checkpoints/<n>`) and `AnchorNotes` (base64 proofs as notes under `refs/notes/anchors/<provider>`, an
unpushed proof or checkpoint kept under the local-only `refs/anchoring/`); the built-in anchor provider
`SignedAnchorProvider` (id `signed`, statement codec `AnchorStatement`, SSH signature in the namespace
`git-document-anchor`, `AllowedSigners.fromAnchor`), and the verification `AnchorVerification.verifyAnchors` (with the
checkpoint chain: `CHECKPOINT_CHAIN_BROKEN`, `CHECKPOINT_DUPLICATE`, `CHECKPOINT_UNREADABLE`) with the
CI main class `VerifyAnchors`. JOL (`org.openjdk.jol:jol-core`) is a test-scope dependency of
`git-store` only, for `IndexFootprintTest` (GDR-NFR-1). Its tests run on the class path against
the `git-testing` fixtures. `git-forms` exports `de.cuioss.git.document.forms` with the closed outcome list
`FormsOutcome`, the result `FormsResult` (whose outcome is a `FormsOutcome` or a non-success store outcome passed on as
`FormsResult.PassedOn`) and the `LogRecord` holder `GitFormsLogMessages`, and with the kinds of documents as
configuration: `KindConfiguration` (loads `kinds.yaml`, at most 1 MiB and never through a symbolic link, from a
directory or a commit of a configuration repository, and checks the pair of repository and collection, the prefix,
the digits and the relative paths) with `Kind` and `Numbering`, over the package-private `YamlReader` (YAML within the
JSON data model, on `org.snakeyaml:snakeyaml-engine`, with the number of digits and the exponent of a number bounded
before it is parsed and the nesting limited to 128 levels), and with schema validation, data layout and codec:
`FormSchema` (a JSON Schema of Draft 2020-12, its declaration order, also through a local `$ref` and `allOf`, and the
split keyword `gdr-split`), `SchemaValidator` (on `dev.harrel:json-schema`,
`required` checked separately) with the sealed `ValidationResult` and `Violation`, `DocumentLayout`, `YamlDocumentCodec`
and `CounterCodec`, over the package-private `DataNode` (the validator's `JsonNode` over the data model),
`SchemaResources` (the schema resources of a file and the resolution of references), `SchemaChecks` (the refusals of
a schema at parse time) with `InPlaceCycles` (a reference cycle that returns to the same value), `MandatoryFields` and
`EvaluatedLocations` (the check of `required`) and
`YamlWriter` (the canonical emitter); and with the generated output, the workflow and step-up:
`DocumentTemplate` (the own minimal template language, a strict subset of Mustache, decided by the owner on
2026-11-23, so no template engine is a dependency), `OutputCheck` and the CI main class `VerifyOutput` (GDR-FORMS-4),
`Workflow` with `Transition`, `Mechanism` and `Deadline` (GDR-FORMS-5, deadlines read but not evaluated),
`StepUpPolicy` (the step-up setting the application passes in, GDR-FORMS-7) and `DocumentTransitions` (the transition
operation over `StoreWriter.draft`, `save` with the per-attempt step and `release`, with its sealed `Result`), over the
package-private `TransitionStep` (the `SaveStep` that writes the state and regenerates the output on every attempt),
`StateMerge` (the merge of the document file onto the tip) and `CloneReader` (the read side of the writer's bare
clone: references, files, the file a draft started from, a saved commit read as an attempt), and behind the facade
`DocumentTransitions` the package-private `TransitionRunner` (the run of one transition: state, lookup, step-up,
logging), `TransitionContext` (the clone reader, layout, codec and output check of a kind, and the lookups per key),
`TransitionSave` (the mechanisms `intermediate-save`, `save` and `release`), `DraftStaging` (the base of the merge and
the draft with the state), `UnchangedSave` (the verification of a save that ran no step and the rewrite of a stale
output), `TransitionCall` (the input of one transition) and `TransitionOutcomes` (the results that end a transition
unperformed, the name of an outcome);
its tests run on the class path as well. `git-timestamp` exports
`de.cuioss.git.document.timestamp` with the anchor provider `Rfc3161AnchorProvider` (id `rfc3161`, built by
`Rfc3161AnchorProvider.from` from the section `timestamp` of the shared configuration map and passed to
`StoreProviders.select` as a candidate; the instance `ServiceLoader` creates is unconfigured and never reaches the
network) and the `LogRecord` holder `GitTimestampLogMessages`, over the package-private `TimestampConfiguration`
(`timestamp.url`, `timestamp.trust`, `timestamp.credentials`), `TimestampTransport` (HTTPS on `java.net.http`, no
redirects, bounded reply), `TimeStampRequests` (the `TimeStampReq` and the reading of the reply), `TimeStampTokens`
(the verification of a token: CMS signature, ESS signing certificate, `id-kp-timeStamping`, PKIX path to
`timestamp.trust` without revocation checking) and `Der` (the bounded DER reader and writer); it adds no dependency.
Its tests run on the class path against an in-process authority on the JDK `HttpsServer` and the `git-testing`
fixtures.
Add an `exports` directive together with the first
public class of a package: javac rejects exporting a package that contains only `package-info.java`.

The build guards GDR-GEN-1 (`doc/specification/general.adoc`) with the `maven-enforcer-plugin` that `cui-parent-pom`
already binds: the execution `module-layering` of the root `pom.xml` bans every reactor dependency outside the module
graph (`git-store` appends the edge `git-core`, `git-forms` and `git-timestamp` each the edge `git-store`), the execution `consumer-isolation` of
`git-core/pom.xml` bans every compile or runtime artifact outside the closure a consumer of `git-core` alone receives,
and the execution `direct-dependency-isolation` of `git-core/pom.xml` bans every declared compile or runtime dependency
outside JSpecify, JGit and `cui-java-tools`, whatever its `optional` flag (the transitive path of `consumer-isolation`
filters optional dependencies).
`DomainTermAbsenceTest` in each module (`git-core`, `git-testing`, `git-store`, `git-forms` and `git-timestamp`) asserts that no public type carries
a domain term. `git-core` ships native-image metadata (`reflect-config.json`, `resource-config.json` under
`META-INF/native-image/de.cuioss/git-core/`, checked by `NativeImageMetadataTest`) for the one JGit translation class
and bundle it reaches (`JGitText`), its worktrees package included; `git-store` ships none and relies on that of `git-core`; `git-forms` ships
`resource-config.json` only, for the bundle and the meta-schema its JSON Schema validator loads; `git-timestamp` ships
none, since its code uses no reflection, resource or resource bundle of its own (GDR-NFR-4).

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
- A new compile dependency of `git-core` is added to the `consumer-isolation` and `direct-dependency-isolation` rules of
  `git-core/pom.xml` only after the owner's approval; until then the build fails.
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
