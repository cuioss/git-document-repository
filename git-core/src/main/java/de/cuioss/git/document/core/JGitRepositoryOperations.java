/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.git.document.core;

import static de.cuioss.git.document.core.GitCoreLogMessages.INFO;
import static de.cuioss.git.document.core.GitCoreLogMessages.WARN;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.jspecify.annotations.Nullable;

/**
 * The repository operations on JGit (checked against JGit 7.8.0).
 * <p>
 * Every repository is opened or created with a {@link RefusingFS}, so no operation starts a process; an operation
 * that would run a present hook or a filter driver ends {@link GitOutcome#REFUSED_BY_POLICY} (GDR-CORE-1). Transport
 * operations contact only remotes the {@link RemotePolicy} admits, checked against the effective URL after
 * {@code insteadOf} rewriting, before anything is sent (GDR-CORE-2). A refused authentication is
 * {@link GitOutcome#CREDENTIALS_INVALID}, every other transport failure {@link GitOutcome#UNREACHABLE} (GDR-CORE-7).
 * <p>
 * Details and log messages render a URL without user information and never carry a credential or a JGit message that
 * may carry one (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class JGitRepositoryOperations implements RepositoryOperations {

    private static final CuiLogger LOGGER = new CuiLogger(JGitRepositoryOperations.class);

    /** The remote a clone is created with. */
    static final String ORIGIN = Constants.DEFAULT_REMOTE_NAME;

    /** The fetch reference specifications of a clone: branches, tags and notes are mirrored. */
    static final List<String> MIRROR_FETCH_REF_SPECS =
            List.of("+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*", "+refs/notes/*:refs/notes/*");

    /** The namespaces a clone mirrors, the destinations a push of a clone may write to. */
    private static final List<String> MIRRORED_NAMESPACES =
            List.of(Constants.R_HEADS, Constants.R_TAGS, Constants.R_NOTES);

    /** The infix of the name of a clone's private staging directory, after the clone's own name. */
    private static final String STAGING_INFIX = ".staging-";

    /** The compiled transport timeout of {@link #fetchCommit(FetchCommitInput)}, in seconds: connect and every read. */
    public static final int FETCH_TIMEOUT_SECONDS = 60;

    /** The references that keep the pinned commits of a cache repository reachable. */
    static final String PINNED_REFS = "refs/pinned/";

    /** The name of the input argument, reported by the null checks. */
    private static final String INPUT_ARGUMENT = "input";

    /** The prefix of a detail that names a remote. */
    private static final String REMOTE_PREFIX = "remote ";

    /** The input field of the start revision of {@code log}. */
    private static final String REVISION = "revision";

    /** The input field of the local reference of {@code push}. */
    private static final String LOCAL_REF = "localRef";

    /** The input field of a pinned commit. */
    private static final String COMMIT = "commit";

    /** The detail suffix of an input field whose value names nothing in the repository. */
    private static final String NOT_FOUND_SUFFIX = ": not found";

    /** The reason of a refused abbreviated revision. */
    private static final String ABBREVIATED_COMMIT_ID = "abbreviated commit id";

    /** The detail of a value that is no full commit id, after the field name. */
    private static final String NOT_A_FULL_COMMIT_ID = ": not a full commit id";

    /** git's {@code upload-pack} refusing a want it does not serve, lower case. */
    private static final String NOT_OUR_REF = "not our ref";

    /** A remote refusing a want by its object id: JGit's {@code UploadPack} or a remote reporting it absent. */
    private static final Pattern WANT_REFUSED =
            Pattern.compile("(?i)\\bwant [0-9a-f]{40}(?:[0-9a-f]{24})? not (?:valid|found)\\b");

    private final FS fs;
    private final RemotePolicy remotePolicy;

    /**
     * Creates the operations.
     *
     * @param remotePolicy the remotes the transport may contact
     * @throws NullPointerException if {@code remotePolicy} is {@code null}
     */
    public JGitRepositoryOperations(RemotePolicy remotePolicy) {
        this.fs = new RefusingFS();
        this.remotePolicy = Objects.requireNonNull(remotePolicy, "remotePolicy");
    }

    @Override
    public GitResult<RepositoryInfo> open(Path root) {
        Objects.requireNonNull(root, "root");
        try (Repository repository = openRepository(root)) {
            return GitResult.ok(info(repository));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<RepositoryInfo> init(Path root, String initialBranch) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(initialBranch, "initialBranch");
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch(initialBranch).setFs(fs).call()) {
            return GitResult.ok(info(git.getRepository()));
        } catch (CapabilityRefusedException e) {
            return refusedCapability(e);
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<RepositoryInfo> cloneIfMissing(CloneInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        Path gitDir = input.gitDir();
        boolean existed = Files.exists(gitDir);
        try {
            if (existed && !isEmptyDirectory(gitDir)) {
                return openExistingClone(gitDir);
            }
        } catch (IOException e) {
            return failure(e);
        }
        URIish uri;
        try {
            uri = new URIish(input.url()).setPass(null);
        } catch (URISyntaxException e) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "not a URL");
        }
        if (!remotePolicy.permits(uri)) {
            return refusedRemote(uri);
        }
        List<RefSpec> pushRefSpecs = new ArrayList<>();
        for (String spec : input.pushRefSpecs()) {
            Optional<RefSpec> mapped = pushRefSpec(spec);
            if (mapped.isEmpty()) {
                return GitResult.of(GitOutcome.REFUSED_BY_POLICY,
                        "pushRefSpecs: not a push to refs/heads/, refs/tags/ or refs/notes/");
            }
            pushRefSpecs.add(mapped.get());
        }
        Path staging = null;
        try {
            staging = createStaging(gitDir);
            GitResult<RepositoryInfo> result = createClone(staging, uri, pushRefSpecs, input);
            if (!result.isOk()) {
                return result;
            }
            if (!publish(staging, gitDir)) {
                // a concurrent attempt published its clone first
                return openExistingClone(gitDir);
            }
            staging = null;
            LOGGER.info(INFO.CLONE_CREATED, gitDir, display(uri));
            try (Repository repository = openGitDir(gitDir)) {
                return GitResult.ok(info(repository));
            }
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException
                | IllegalArgumentException e) {
            return transportFailure(e, ORIGIN, display(uri));
        } finally {
            removeStaging(staging);
        }
    }

    /**
     * Maps a push reference specification of a clone to the one configured, and refuses one the clone cannot restore
     * (GDR-CORE-3). A source-only specification, wildcard or not, pushes to the reference of the same name: JGit 7.8.0
     * {@code new RefSpec("refs/heads/*")} rejects a source-only wildcard, so {@code refs/heads/*} is configured as
     * {@code refs/heads/*:refs/heads/*}. The destination must lie in a namespace the clone mirrors
     * ({@link #MIRROR_FETCH_REF_SPECS}), otherwise a fresh clone would not fetch what was pushed.
     *
     * @param spec the caller's push reference specification
     * @return the specification to configure, empty when it is invalid, deletes, matches or leaves the mirrored
     *         namespaces
     */
    static Optional<RefSpec> pushRefSpec(String spec) {
        try {
            var parsed = new RefSpec(spec, RefSpec.WildcardMode.ALLOW_MISMATCH);
            String source = parsed.getSource();
            if (parsed.isNegative() || parsed.isMatching() || source == null) {
                return Optional.empty();
            }
            String destination = parsed.getDestination() == null ? source : parsed.getDestination();
            if (MIRRORED_NAMESPACES.stream().noneMatch(destination::startsWith)) {
                return Optional.empty();
            }
            return Optional.of(new RefSpec((parsed.isForceUpdate() ? "+" : "") + source + ":" + destination));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Creates the private staging directory of one clone attempt, beside the clone so the publishing move stays on
     * one file system.
     */
    private static Path createStaging(Path gitDir) throws IOException {
        Path parent = gitDir.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "." + gitDir.getFileName() + STAGING_INFIX);
    }

    /**
     * Publishes a complete clone by moving its staging directory to the clone's place in one atomic step, so the
     * clone's place holds either nothing, the empty directory the caller prepared, or a complete clone, never a
     * partial one.
     *
     * @return {@code true} if this attempt published the clone, {@code false} if another one published first
     */
    private static boolean publish(Path staging, Path gitDir) throws IOException {
        try {
            if (Files.isDirectory(gitDir)) {
                // the empty directory the caller prepared; a non-empty one is a clone published concurrently
                Files.delete(gitDir);
            }
            Files.move(staging, gitDir, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
            return false;
        }
    }

    /** Removes the staging directory of this attempt, and only that one. */
    private static void removeStaging(@Nullable Path staging) {
        if (staging == null) {
            return;
        }
        try {
            FileUtils.delete(staging.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING | FileUtils.RETRY);
        } catch (IOException e) {
            LOGGER.debug("Could not remove the clone staging directory %s: %s", staging,
                    e.getClass().getSimpleName());
        }
    }

    private GitResult<RepositoryInfo> openExistingClone(Path gitDir) throws IOException {
        if (!RepositoryCache.FileKey.isGitRepository(gitDir.toFile(), fs)) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, gitDir + " is no repository");
        }
        try (Repository repository = openGitDir(gitDir)) {
            return repository.isBare() ? GitResult.ok(info(repository))
                    : GitResult.of(GitOutcome.NOT_A_REPOSITORY, gitDir + " is no bare repository");
        }
    }

    /** Builds the complete clone in its staging directory: the bare repository, origin, and the fetch. */
    private GitResult<RepositoryInfo> createClone(Path staging, URIish uri, List<RefSpec> pushRefSpecs,
            CloneInput input) throws GitAPIException, IOException, URISyntaxException {
        try (Git git = Git.init().setBare(true).setGitDir(staging.toFile()).setFs(fs).call()) {
            Repository repository = git.getRepository();
            StoredConfig config = repository.getConfig();
            var remote = new RemoteConfig(config, ORIGIN);
            remote.addURI(uri);
            MIRROR_FETCH_REF_SPECS.forEach(spec -> remote.addFetchRefSpec(new RefSpec(spec)));
            pushRefSpecs.forEach(remote::addPushRefSpec);
            remote.update(config);
            config.save();
            Optional<GitResult<RepositoryInfo>> refused = checkRemote(repository, ORIGIN, false);
            if (refused.isPresent()) {
                return refused.get();
            }
            FetchResult fetched = git.fetch().setRemote(ORIGIN)
                    .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                    .setTransportConfigCallback(onlyCredentials(input.credentials())).call();
            linkHead(repository, fetched);
            return GitResult.ok(info(repository));
        }
    }

    /** Points {@code HEAD} of a new clone at the branch the remote's {@code HEAD} points to, when it advertises one. */
    private static void linkHead(Repository repository, FetchResult fetched) throws IOException {
        Ref remoteHead = fetched.getAdvertisedRef(Constants.HEAD);
        if (remoteHead == null || !remoteHead.isSymbolic()) {
            return;
        }
        String target = remoteHead.getTarget().getName();
        if (repository.exactRef(target) != null) {
            repository.updateRef(Constants.HEAD).link(target);
        }
    }


    @Override
    public GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(revision, "revision");
        try (Repository repository = openRepository(root); RevWalk walk = new RevWalk(repository)) {
            var lookup = GitIdentity.lookup(repository, revision);
            Optional<GitResult<List<CommitInfo>>> refused = refuseUnsupported(lookup, REVISION);
            if (refused.isPresent()) {
                return refused.get();
            }
            Optional<ObjectId> resolved = resolveCommit(repository, lookup);
            if (resolved.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, REVISION + NOT_FOUND_SUFFIX);
            }
            ObjectId start = resolved.get();
            if (lookup.resolution() == GitIdentity.Resolution.FULL_COMMIT_ID
                    && !start.name().equalsIgnoreCase(revision)) {
                return GitResult.of(GitOutcome.IDENTITY_MISMATCH,
                        "the pin " + revision + " reached " + start.name());
            }
            walk.markStart(walk.parseCommit(start));
            int limit = Math.max(1, maxCount);
            var commits = new ArrayList<CommitInfo>();
            for (RevCommit commit : walk) {
                if (commits.size() >= limit) {
                    break;
                }
                commits.add(toInfo(commit));
            }
            return GitResult.ok(List.copyOf(commits));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<List<RefUpdate>> fetch(FetchInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        String target = REMOTE_PREFIX + input.remote();
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<List<RefUpdate>>> refused = checkRemote(repository, input.remote(), false);
            if (refused.isPresent()) {
                return refused.get();
            }
            target = describeRemote(repository, input.remote(), false);
            FetchResult result = git.fetch()
                    .setRemote(input.remote())
                    .setRefSpecs(input.refSpecs().stream().map(RefSpec::new).toList())
                    // JGit 7.8.0 FetchCommand fetches submodules of a working tree on demand, each from its own
                    // remote that the policy check above never saw
                    .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                    .setTransportConfigCallback(onlyCredentials(input.credentials()))
                    .call();
            var updates = result.getTrackingRefUpdates().stream()
                    .map(update -> RefUpdate.builder()
                            .ref(update.getLocalName())
                            .oldId(id(update.getOldObjectId()).orElse(null))
                            .newId(id(update.getNewObjectId()).orElse(null))
                            .status(update.getResult().name().toLowerCase(Locale.ROOT))
                            .build())
                    .toList();
            return GitResult.ok(updates);
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException
                | IllegalArgumentException e) {
            return transportFailure(e, input.remote(), target);
        }
    }

    @Override
    public GitResult<RefUpdate> push(PushInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        String target = REMOTE_PREFIX + input.remote();
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<RefUpdate>> refused = checkRemote(repository, input.remote(), true);
            if (refused.isPresent()) {
                return refused.get();
            }
            target = describeRemote(repository, input.remote(), true);
            if (remoteUris(repository, input.remote(), true).size() > 1) {
                // JGit 7.8.0 PushCommand pushes to every push URL and reports one PushResult each, so destinations
                // could disagree; a push is refused before contact instead
                return GitResult.of(GitOutcome.REFUSED_BY_POLICY, target + ": more than one push destination");
            }
            var lookup = GitIdentity.lookup(repository, input.localRef());
            Optional<GitResult<RefUpdate>> unsupported = refuseUnsupported(lookup, LOCAL_REF);
            if (unsupported.isPresent()) {
                return unsupported.get();
            }
            ObjectId local = repository.resolve(lookup.target().orElseThrow());
            if (local == null || !repository.getObjectDatabase().has(local)) {
                return GitResult.of(GitOutcome.NOT_FOUND, LOCAL_REF + NOT_FOUND_SUFFIX);
            }
            var command = git.push()
                    .setRemote(input.remote())
                    .setRefSpecs(new RefSpec(local.name() + ":" + input.remoteRef()))
                    .setTransportConfigCallback(onlyCredentials(input.credentials()));
            String expectedRemoteHead = input.expectedRemoteHead();
            if (expectedRemoteHead != null) {
                command.setRefLeaseSpecs(new RefLeaseSpec(input.remoteRef(), expectedRemoteHead));
            }
            for (PushResult result : command.call()) {
                RemoteRefUpdate update = result.getRemoteUpdate(input.remoteRef());
                if (update != null) {
                    return toPushResult(update, result.getAdvertisedRef(input.remoteRef()), expectedRemoteHead);
                }
            }
            return GitResult.of(GitOutcome.FAILED, "remoteRef: no update reported");
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException
                | IllegalArgumentException e) {
            return transportFailure(e, input.remote(), target);
        }
    }

    @Override
    public GitResult<Path> initCache(Path cache) {
        Objects.requireNonNull(cache, "cache");
        try {
            if (Files.exists(cache) && !isEmptyDirectory(cache)) {
                try (Repository repository = openGitDir(cache)) {
                    return repository.isBare() ? GitResult.ok(cache.toRealPath())
                            : GitResult.of(GitOutcome.NOT_A_REPOSITORY, cache + " is no bare repository");
                }
            }
            Files.createDirectories(cache);
            Git.init().setBare(true).setGitDir(cache.toFile()).setFs(fs).call().close();
            return GitResult.ok(cache.toRealPath());
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, cache.toString());
        } catch (CapabilityRefusedException e) {
            return refusedCapability(e);
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<String> fetchCommit(FetchCommitInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        URIish uri;
        try {
            // the fetch is anonymous: user information in the URL is removed before the transport sees it
            uri = new URIish(input.url()).setUser(null).setPass(null);
        } catch (URISyntaxException e) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "not a URL");
        }
        if (!input.policy().permits(uri)) {
            return refusedRemote(uri);
        }
        if (!ObjectId.isId(input.commit())) {
            return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + NOT_A_FULL_COMMIT_ID);
        }
        var commit = ObjectId.fromString(input.commit());
        try (Repository repository = openCache(input.cache())) {
            if (!repository.isBare()) {
                return noBareCache(input.cache());
            }
            if (isCommit(repository, commit)) {
                // a fetch whose pin failed left the object without its reference; finish the pin without contact
                pin(repository, commit);
                return GitResult.ok(commit.name());
            }
            try (Transport transport = Transport.open(repository, uri)) {
                if (!input.policy().permits(transport.getURI())) {
                    return refusedRemote(transport.getURI());
                }
                if (transport.getURI().getUser() != null || transport.getURI().getPass() != null) {
                    return GitResult.of(GitOutcome.REFUSED_BY_POLICY,
                            display(transport.getURI()) + ": user information in an anonymous fetch");
                }
                // JGit 7.8.0 Transport starts with CredentialsProvider.getDefault(), the process-wide provider
                transport.setCredentialsProvider(null);
                transport.setTimeout(FETCH_TIMEOUT_SECONDS);
                transport.setTagOpt(TagOpt.NO_TAGS);
                transport.setCheckFetchedObjects(true);
                transport.fetch(NullProgressMonitor.INSTANCE,
                        List.of(new RefSpec("+" + commit.name() + ":" + PINNED_REFS + commit.name())));
            }
            if (!isCommit(repository, commit)) {
                return GitResult.of(GitOutcome.UNSERVED, display(uri) + " did not serve " + commit.name());
            }
            pin(repository, commit);
            return GitResult.ok(commit.name());
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.cache().toString());
        } catch (CapabilityRefusedException e) {
            return refusedCapability(e);
        } catch (TransportException e) {
            return isUnservedWant(e.getMessage())
                    ? GitResult.of(GitOutcome.UNSERVED, display(uri) + " did not serve " + commit.name())
                    : transportFailure(e, display(uri), display(uri));
        } catch (IOException | JGitInternalException e) {
            return transportFailure(e, display(uri), display(uri));
        }
    }

    @Override
    public GitResult<byte[]> readFile(ReadFileInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        if (!ObjectId.isId(input.commit())) {
            return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + NOT_A_FULL_COMMIT_ID);
        }
        var commitId = ObjectId.fromString(input.commit());
        try (Repository repository = openCache(input.cache()); RevWalk walk = new RevWalk(repository)) {
            if (!repository.isBare()) {
                return noBareCache(input.cache());
            }
            if (!isCommit(repository, commitId)) {
                return GitResult.of(GitOutcome.NOT_FOUND, "commit " + commitId.name() + " is not in the cache");
            }
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tree = TreeWalk.forPath(repository, input.path(), commit.getTree())) {
                if (tree == null || (tree.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
                    return GitResult.of(GitOutcome.NOT_FOUND, "path: no file at " + commitId.name());
                }
                try (var content = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).openStream()) {
                    return GitResult.ok(content.readAllBytes());
                }
            }
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.cache().toString());
        } catch (IllegalArgumentException e) {
            return GitResult.of(GitOutcome.NOT_FOUND, "path: not a valid path");
        } catch (IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    /**
     * Points {@code refs/pinned/<id>} at a fetched commit, so it stays reachable. JGit 7.8.0 ignores the destination
     * of a fetch reference specification whose source is an object id ({@code FetchProcess.expandSingle} only wants
     * the object), so the reference is written here.
     */
    private static void pin(Repository repository, ObjectId commit) throws IOException {
        var update = repository.updateRef(PINNED_REFS + commit.name());
        update.setNewObjectId(commit);
        update.setForceUpdate(true);
        var result = update.update();
        if (result != org.eclipse.jgit.lib.RefUpdate.Result.NEW && result != org.eclipse.jgit.lib.RefUpdate.Result.FORCED
                && result != org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE) {
            throw new IOException("pinned reference not written: " + result);
        }
    }

    /**
     * Opens a cache repository as its configuration describes it. JGit 7.8.0 {@code BaseRepositoryBuilder.setBare()}
     * would override {@code core.bare} and open the git directory of a working tree as bare, so the caller checks
     * {@link Repository#isBare()} instead.
     */
    private Repository openCache(Path cache) throws IOException {
        return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setGitDir(cache.toFile()).build();
    }

    private static <V> GitResult<V> noBareCache(Path cache) {
        return GitResult.of(GitOutcome.NOT_A_REPOSITORY, cache + " is no bare repository");
    }

    private static boolean isCommit(Repository repository, ObjectId id) throws IOException {
        if (!repository.getObjectDatabase().has(id)) {
            return false;
        }
        return repository.open(id).getType() == Constants.OBJ_COMMIT;
    }

    /**
     * Recognises the closed set of messages by which a remote that answered refuses to serve a wanted object; the test
     * ignores case. A transport failure with any other message is {@link GitOutcome#UNREACHABLE}.
     * <ul>
     * <li>{@code not our ref}: the wording of git's {@code upload-pack}, which GitHub and GitLab run, for a want it
     * does not advertise or no longer has, e.g. {@code upload-pack: not our ref <sha>} or
     * {@code remote error: upload-pack: not our ref <sha>}.</li>
     * <li>{@code want <object id> not valid}: the wording of JGit's {@code UploadPack}.</li>
     * <li>{@code want <object id> not found}: a want the remote reports as absent.</li>
     * </ul>
     *
     * @param message the message of a transport failure; {@code null} if it has none
     * @return {@code true} if the message is one of the set
     */
    static boolean isUnservedWant(@Nullable String message) {
        if (message == null) {
            return false;
        }
        return message.toLowerCase(Locale.ROOT).contains(NOT_OUR_REF) || WANT_REFUSED.matcher(message).find();
    }

    /**
     * Refuses a revision that is no supported identity (GDR-CORE-11): an ambiguous one with its candidates, an
     * abbreviated commit id, and everything else that is neither exactly one reference nor a full commit id, a
     * revision expression included. The detail names the field and the reason, never the caller's value (GDR-NFR-3);
     * the candidates of an ambiguity are references of the repository.
     *
     * @param field the name of the input field that carries the revision
     * @return the refusal, or empty when the revision may be resolved
     */
    private static <V> Optional<GitResult<V>> refuseUnsupported(GitIdentity.Lookup lookup, String field) {
        if (GitIdentity.isAmbiguous(lookup.resolution())) {
            return Optional.of(GitResult.of(GitOutcome.AMBIGUOUS,
                    field + " names " + String.join(", ", lookup.candidates())));
        }
        if (lookup.resolution() == GitIdentity.Resolution.ABBREVIATED_COMMIT_ID) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, field + ": " + ABBREVIATED_COMMIT_ID));
        }
        if (!lookup.isSupported()) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, field + NOT_FOUND_SUFFIX));
        }
        return Optional.empty();
    }

    /**
     * Resolves a supported identity to its commit. A full commit id whose object is absent from the repository is not
     * found: JGit 7.8.0 {@code Repository.resolve("<id>^{commit}")} throws {@link MissingObjectException} for it.
     *
     * @return the commit, or empty when the identity names no commit of the repository
     */
    private static Optional<ObjectId> resolveCommit(Repository repository, GitIdentity.Lookup lookup)
            throws IOException {
        try {
            return Optional.ofNullable(repository.resolve(lookup.target().orElseThrow() + "^{commit}"));
        } catch (MissingObjectException e) {
            return Optional.empty();
        }
    }

    /**
     * Maps the update of the pushed reference to the result.
     * <p>
     * JGit 7.8.0 {@code PushProcess} reports {@code UP_TO_DATE} as soon as the advertised id equals the new id, before
     * it compares the lease and before it sets the expected old id. An up-to-date update is therefore checked here
     * against the lease, from the advertised reference of the same push, and its old id is the advertised id: the
     * reference existed and was not created.
     *
     * @param update             the update of the pushed reference
     * @param advertised         the remote reference the push saw; {@code null} if the remote had none
     * @param expectedRemoteHead the lease; {@code null} for a plain push
     */
    private static GitResult<RefUpdate> toPushResult(RemoteRefUpdate update, @Nullable Ref advertised,
            @Nullable String expectedRemoteHead) {
        String status = update.getStatus().name().toLowerCase(Locale.ROOT);
        boolean upToDate = update.getStatus() == RemoteRefUpdate.Status.UP_TO_DATE;
        Optional<String> advertisedId = advertised == null ? Optional.empty() : id(advertised.getObjectId());
        if (upToDate && expectedRemoteHead != null
                && !advertisedId.map(expectedRemoteHead::equalsIgnoreCase).orElse(false)) {
            return GitResult.of(GitOutcome.LEASE_REFUSED, update.getRemoteName());
        }
        Optional<String> oldId = upToDate ? advertisedId.or(() -> id(update.getNewObjectId()))
                : id(update.getExpectedOldObjectId());
        var ref = RefUpdate.builder()
                .ref(update.getRemoteName())
                .oldId(oldId.orElse(null))
                .newId(id(update.getNewObjectId()).orElse(null))
                .status(status)
                .build();
        return switch (update.getStatus()) {
            case OK, UP_TO_DATE -> GitResult.ok(ref);
            case REJECTED_NONFASTFORWARD, REJECTED_OTHER_REASON, REJECTED_NODELETE ->
                GitResult.of(GitOutcome.REJECTED, update.getRemoteName() + ": " + status);
            case REJECTED_REMOTE_CHANGED -> GitResult.of(GitOutcome.LEASE_REFUSED, update.getRemoteName());
            default -> GitResult.of(GitOutcome.FAILED, update.getRemoteName() + ": " + status);
        };
    }

    /**
     * Checks every effective URL of a remote against the policy, before anything is sent.
     *
     * @return the refusal, or empty when the remote may be contacted
     */
    private <V> Optional<GitResult<V>> checkRemote(Repository repository, String remote, boolean push)
            throws URISyntaxException {
        List<URIish> uris = remoteUris(repository, remote, push);
        if (uris.isEmpty()) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, REMOTE_PREFIX + remote));
        }
        for (URIish uri : uris) {
            if (!remotePolicy.permits(uri)) {
                return Optional.of(refusedRemote(uri));
            }
        }
        return Optional.empty();
    }

    /** The effective URLs of a remote, after {@code insteadOf} rewriting. */
    private static List<URIish> remoteUris(Repository repository, String remote, boolean push)
            throws URISyntaxException {
        var config = new RemoteConfig(repository.getConfig(), remote);
        return push && !config.getPushURIs().isEmpty() ? config.getPushURIs() : config.getURIs();
    }

    private static String describeRemote(Repository repository, String remote, boolean push)
            throws URISyntaxException {
        return REMOTE_PREFIX + remote + " ("
                + remoteUris(repository, remote, push).stream().map(JGitRepositoryOperations::display)
                .collect(Collectors.joining(", "))
                + ")";
    }

    private static <V> GitResult<V> refusedRemote(URIish uri) {
        String shown = display(uri);
        LOGGER.warn(WARN.REMOTE_REFUSED, shown);
        return GitResult.of(GitOutcome.REFUSED_BY_POLICY, REMOTE_PREFIX + shown + " is outside the remote policy");
    }

    private static <V> GitResult<V> refusedCapability(CapabilityRefusedException refusal) {
        LOGGER.warn(WARN.CAPABILITY_REFUSED, refusal.getReason());
        return GitResult.of(GitOutcome.REFUSED_BY_POLICY, refusal.getReason());
    }

    /**
     * Maps the failure of a transport operation to its outcome. The detail names the remote and the failure class
     * only, never the failure's message, which may carry user information or a credential.
     */
    private static <V> GitResult<V> transportFailure(Exception failure, String remote, String target) {
        Optional<CapabilityRefusedException> refusal = cause(failure, CapabilityRefusedException.class);
        if (refusal.isPresent()) {
            return refusedCapability(refusal.get());
        }
        if (failure instanceof URISyntaxException) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, REMOTE_PREFIX + remote + " has no valid URL");
        }
        if (cause(failure, NoRemoteRepositoryException.class).isPresent()) {
            return GitResult.of(GitOutcome.UNREACHABLE, target + ": no repository");
        }
        if (failure instanceof InvalidRemoteException) {
            return GitResult.of(GitOutcome.NOT_FOUND, REMOTE_PREFIX + remote);
        }
        if (failure instanceof org.eclipse.jgit.api.errors.TransportException
                || cause(failure, TransportException.class).isPresent()) {
            GitOutcome outcome = TransportFailures.classify(failure);
            return GitResult.of(outcome, target + ": " + (outcome == GitOutcome.CREDENTIALS_INVALID
                    ? "authentication refused" : "transport failed"));
        }
        return GitResult.of(GitOutcome.FAILED, target + ": " + failure.getClass().getSimpleName());
    }

    private static <T extends Throwable> Optional<T> cause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
        }
        return Optional.empty();
    }

    /**
     * Opens the repository at {@code root}: the working tree containing a {@code .git} entry, the bare repository whose
     * git directory {@code root} is, or the repository found by walking up from {@code root}.
     */
    private Repository openRepository(Path root) throws IOException {
        if (Files.exists(root.resolve(Constants.DOT_GIT))) {
            return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setWorkTree(root.toFile()).build();
        }
        if (RepositoryCache.FileKey.isGitRepository(root.toFile(), fs)) {
            return openGitDir(root);
        }
        var builder = new FileRepositoryBuilder().setFS(fs).setMustExist(true);
        builder.findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            throw new RepositoryNotFoundException(root.toFile());
        }
        return builder.build();
    }

    private Repository openGitDir(Path gitDir) throws IOException {
        return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setGitDir(gitDir.toFile()).build();
    }

    private static RepositoryInfo info(Repository repository) throws IOException {
        Path gitDir = repository.getDirectory().toPath().toRealPath();
        Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
        Ref head = repository.exactRef(Constants.HEAD);
        Optional<String> branch = head != null && head.isSymbolic()
                ? Optional.of(Repository.shortenRefName(head.getTarget().getName()))
                : Optional.empty();
        // the commit of the same HEAD observation as the branch; null on an unborn branch
        Optional<String> id = head == null ? Optional.empty() : id(head.getObjectId());
        return RepositoryInfo.builder()
                .worktreeRoot(repository.isBare() ? null : repository.getWorkTree().toPath().toRealPath())
                .gitDir(gitDir)
                .commonDir(commonDir)
                .head(id.orElse(null))
                .branch(branch.orElse(null))
                .linked(!gitDir.equals(commonDir))
                .build();
    }

    private static CommitInfo toInfo(RevCommit commit) {
        var parents = Arrays.stream(commit.getParents()).map(RevCommit::name).toList();
        PersonIdent author = commit.getAuthorIdent();
        PersonIdent committer = commit.getCommitterIdent();
        return CommitInfo.builder()
                .id(commit.name())
                .parents(parents)
                .author(new Identity(author.getName(), author.getEmailAddress()))
                .authoredAt(author.getWhenAsInstant())
                .committer(new Identity(committer.getName(), committer.getEmailAddress()))
                .committedAt(committer.getWhenAsInstant())
                .message(commit.getFullMessage())
                .build();
    }

    private static Optional<String> id(@Nullable ObjectId id) {
        return id == null || ObjectId.zeroId().equals(id) ? Optional.empty() : Optional.of(id.name());
    }

    /**
     * Renders a URL for a detail or a log message from an allowlist of its components: scheme, host, port and path.
     * User information is omitted, and so are query data and a fragment, which JGit 7.8.0 {@link URIish} keeps in its
     * path and renders. The URL the transport uses is not changed.
     */
    static String display(URIish uri) {
        String path = uri.getPath();
        if (path != null) {
            int end = indexOfQueryOrFragment(path);
            path = end < 0 ? path : path.substring(0, end);
        }
        return new URIish().setScheme(uri.getScheme()).setHost(uri.getHost()).setPort(uri.getPort()).setPath(path)
                .toString();
    }

    private static int indexOfQueryOrFragment(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        if (query < 0) {
            return fragment;
        }
        return fragment < 0 ? query : Math.min(query, fragment);
    }

    /** A transport configuration that uses exactly the caller's credentials, none when the caller supplies none. */
    private static TransportConfigCallback onlyCredentials(@Nullable CredentialsProvider credentials) {
        // JGit 7.8.0 TransportCommand starts with CredentialsProvider.getDefault() and keeps it when given null
        return transport -> transport.setCredentialsProvider(credentials);
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    /** The failure class only: the message of a failure may carry the caller's input (GDR-NFR-3). */
    private static <V> GitResult<V> failure(Exception e) {
        return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName());
    }

    /**
     * Classifies a transport failure (GDR-CORE-7): a refused authentication is
     * {@link GitOutcome#CREDENTIALS_INVALID}, every other transport failure {@link GitOutcome#UNREACHABLE}.
     * <p>
     * The refused authentication is recognised by the wordings JGit 7.8.0 uses for it (checked against
     * {@code JGitText.properties} and {@code TransportHttp}); the test ignores case:
     * <ul>
     * <li>{@code not authorized}: HTTP 401 after the credentials were tried ({@code notAuthorized}).</li>
     * <li>{@code authentication is required but no CredentialsProvider has been registered}: HTTP 401 without
     * credentials ({@code noCredentialsProvider}).</li>
     * <li>{@code authentication not supported}: HTTP 401 with no authentication method JGit supports
     * ({@code authenticationNotSupported}).</li>
     * <li>{@code <service> not permitted on '<url>'}: HTTP 403 ({@code serviceNotPermitted}).</li>
     * </ul>
     *
     * @since 0.1
     */
    static final class TransportFailures {

        /** The lower-case wordings of a refused authentication. */
        private static final List<String> REFUSED_AUTHENTICATION = List.of("not authorized",
                "authentication is required but no credentialsprovider has been registered",
                "authentication not supported", " not permitted on '");

        private TransportFailures() {
        }

        /**
         * Classifies a transport failure by the messages of its cause chain.
         *
         * @param failure the failure
         * @return {@link GitOutcome#CREDENTIALS_INVALID} if a message of the chain is a refused authentication,
         *         {@link GitOutcome#UNREACHABLE} otherwise
         */
        static GitOutcome classify(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (isRefusedAuthentication(current.getMessage())) {
                    return GitOutcome.CREDENTIALS_INVALID;
                }
            }
            return GitOutcome.UNREACHABLE;
        }

        /**
         * Tells whether a transport message is a refused authentication.
         *
         * @param message the message; {@code null} if there is none
         * @return whether the message contains one of the wordings of a refused authentication
         */
        static boolean isRefusedAuthentication(@Nullable String message) {
            if (message == null) {
                return false;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            return REFUSED_AUTHENTICATION.stream().anyMatch(lower::contains);
        }
    }
}
