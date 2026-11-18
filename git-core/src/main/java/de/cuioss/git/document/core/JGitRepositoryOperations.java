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
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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

    /** The compiled transport timeout of {@link #fetchCommit(FetchCommitInput)}, in seconds: connect and every read. */
    public static final int FETCH_TIMEOUT_SECONDS = 60;

    /** The references that keep the pinned commits of a cache repository reachable. */
    static final String PINNED_REFS = "refs/pinned/";

    /** The detail prefix of a refused abbreviated revision. */
    private static final String ABBREVIATED_COMMIT_ID = "abbreviated commit id: ";

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
        Objects.requireNonNull(input, "input");
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
        try {
            GitResult<RepositoryInfo> result = createClone(gitDir, uri, input);
            if (!result.isOk()) {
                removeClone(gitDir, existed);
            }
            return result;
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException e) {
            removeClone(gitDir, existed);
            return transportFailure(e, ORIGIN, display(uri));
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

    private GitResult<RepositoryInfo> createClone(Path gitDir, URIish uri, CloneInput input)
            throws GitAPIException, IOException, URISyntaxException {
        Files.createDirectories(gitDir);
        try (Git git = Git.init().setBare(true).setGitDir(gitDir.toFile()).setFs(fs).call()) {
            Repository repository = git.getRepository();
            StoredConfig config = repository.getConfig();
            var remote = new RemoteConfig(config, ORIGIN);
            remote.addURI(uri);
            MIRROR_FETCH_REF_SPECS.forEach(spec -> remote.addFetchRefSpec(new RefSpec(spec)));
            input.pushRefSpecs().forEach(spec -> remote.addPushRefSpec(new RefSpec(spec)));
            remote.update(config);
            config.save();
            Optional<GitResult<RepositoryInfo>> refused = checkRemote(repository, ORIGIN, false);
            if (refused.isPresent()) {
                return refused.get();
            }
            FetchResult fetched = git.fetch().setRemote(ORIGIN).setCredentialsProvider(input.credentials()).call();
            linkHead(repository, fetched);
            LOGGER.info(INFO.CLONE_CREATED, gitDir, display(uri));
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

    /** Removes a clone that could not be completed; a directory that existed empty before is left empty. */
    private static void removeClone(Path gitDir, boolean existed) {
        try {
            FileUtils.delete(gitDir.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING | FileUtils.RETRY);
            if (existed) {
                Files.createDirectories(gitDir);
            }
        } catch (IOException e) {
            LOGGER.debug("Could not remove the incomplete clone at %s: %s", gitDir, e.getClass().getSimpleName());
        }
    }

    @Override
    public GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(revision, "revision");
        try (Repository repository = openRepository(root); RevWalk walk = new RevWalk(repository)) {
            var resolution = GitIdentity.resolution(repository, revision);
            if (GitIdentity.isAmbiguous(resolution)) {
                return GitResult.of(GitOutcome.AMBIGUOUS,
                        revision + " names " + String.join(", ", GitIdentity.candidates(repository, revision)));
            }
            if (resolution == GitIdentity.Resolution.ABBREVIATED_COMMIT_ID) {
                return GitResult.of(GitOutcome.NOT_FOUND, ABBREVIATED_COMMIT_ID + revision);
            }
            ObjectId start = repository.resolve(revision + "^{commit}");
            if (start == null) {
                return GitResult.of(GitOutcome.NOT_FOUND, "revision " + revision);
            }
            if (resolution == GitIdentity.Resolution.FULL_COMMIT_ID && !start.name().equalsIgnoreCase(revision)) {
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
        Objects.requireNonNull(input, "input");
        String target = "remote " + input.remote();
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<List<RefUpdate>>> refused = checkRemote(repository, input.remote(), false);
            if (refused.isPresent()) {
                return refused.get();
            }
            target = describeRemote(repository, input.remote(), false);
            FetchResult result = git.fetch()
                    .setRemote(input.remote())
                    .setRefSpecs(input.refSpecs().stream().map(RefSpec::new).toList())
                    .setCredentialsProvider(input.credentials())
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
        Objects.requireNonNull(input, "input");
        String target = "remote " + input.remote();
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            Optional<GitResult<RefUpdate>> refused = checkRemote(repository, input.remote(), true);
            if (refused.isPresent()) {
                return refused.get();
            }
            target = describeRemote(repository, input.remote(), true);
            ObjectId local = repository.resolve(input.localRef());
            if (local == null) {
                return GitResult.of(GitOutcome.NOT_FOUND, "revision " + input.localRef());
            }
            var command = git.push()
                    .setRemote(input.remote())
                    .setRefSpecs(new RefSpec(local.name() + ":" + input.remoteRef()))
                    .setCredentialsProvider(input.credentials());
            String expectedRemoteHead = input.expectedRemoteHead();
            if (expectedRemoteHead != null) {
                command.setRefLeaseSpecs(new RefLeaseSpec(input.remoteRef(), expectedRemoteHead));
            }
            for (PushResult result : command.call()) {
                RemoteRefUpdate update = result.getRemoteUpdate(input.remoteRef());
                if (update != null) {
                    return toPushResult(update);
                }
            }
            return GitResult.of(GitOutcome.FAILED, "no update reported for " + input.remoteRef());
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
        Objects.requireNonNull(input, "input");
        URIish uri;
        try {
            uri = new URIish(input.url());
        } catch (URISyntaxException e) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "not a URL");
        }
        if (!input.policy().permits(uri)) {
            return refusedRemote(uri);
        }
        if (!ObjectId.isId(input.commit())) {
            return GitResult.of(GitOutcome.NOT_FOUND, "not a full commit id: " + input.commit());
        }
        var commit = ObjectId.fromString(input.commit());
        try (Repository repository = openCache(input.cache())) {
            if (isCommit(repository, commit)) {
                return GitResult.ok(commit.name());
            }
            try (Transport transport = Transport.open(repository, uri)) {
                if (!input.policy().permits(transport.getURI())) {
                    return refusedRemote(transport.getURI());
                }
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
        Objects.requireNonNull(input, "input");
        if (!ObjectId.isId(input.commit())) {
            return GitResult.of(GitOutcome.NOT_FOUND, "not a full commit id: " + input.commit());
        }
        var commitId = ObjectId.fromString(input.commit());
        try (Repository repository = openCache(input.cache()); RevWalk walk = new RevWalk(repository)) {
            if (!isCommit(repository, commitId)) {
                return GitResult.of(GitOutcome.NOT_FOUND, "commit " + commitId.name() + " is not in the cache");
            }
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tree = TreeWalk.forPath(repository, input.path(), commit.getTree())) {
                if (tree == null || (tree.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
                    return GitResult.of(GitOutcome.NOT_FOUND, "path " + input.path() + " at " + commitId.name());
                }
                try (var content = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).openStream()) {
                    return GitResult.ok(content.readAllBytes());
                }
            }
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.cache().toString());
        } catch (IllegalArgumentException e) {
            return GitResult.of(GitOutcome.NOT_FOUND, "path " + input.path() + " is no valid path");
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

    private Repository openCache(Path cache) throws IOException {
        return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setGitDir(cache.toFile()).setBare().build();
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

    private static GitResult<RefUpdate> toPushResult(RemoteRefUpdate update) {
        String status = update.getStatus().name().toLowerCase(Locale.ROOT);
        var ref = RefUpdate.builder()
                .ref(update.getRemoteName())
                .oldId(id(update.getExpectedOldObjectId()).orElse(null))
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
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, "remote " + remote));
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
        return "remote " + remote + " ("
                + remoteUris(repository, remote, push).stream().map(JGitRepositoryOperations::display)
                .collect(Collectors.joining(", "))
                + ")";
    }

    private static <V> GitResult<V> refusedRemote(URIish uri) {
        String shown = display(uri);
        LOGGER.warn(WARN.REMOTE_REFUSED, shown);
        return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "remote " + shown + " is outside the remote policy");
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
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "remote " + remote + " has no valid URL");
        }
        if (cause(failure, NoRemoteRepositoryException.class).isPresent()) {
            return GitResult.of(GitOutcome.UNREACHABLE, target + ": no repository");
        }
        if (failure instanceof InvalidRemoteException) {
            return GitResult.of(GitOutcome.NOT_FOUND, "remote " + remote);
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
        Optional<String> id = Optional.ofNullable(repository.resolve(Constants.HEAD)).map(ObjectId::name);
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

    /** Renders a URL without user information. */
    private static String display(URIish uri) {
        return uri.setUser(null).setPass(null).toString();
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private static <V> GitResult<V> failure(Exception e) {
        return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
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
