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
import java.io.Serial;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemException;
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
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
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
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.jspecify.annotations.Nullable;

/**
 * The repository operations on JGit (checked against JGit 7.8.0).
 * <p>
 * Every repository is opened or created with a {@link RefusingFS}, so no operation starts a process through the
 * repository's {@code FS}; an operation that would run a present hook or a filter driver ends
 * {@link GitOutcome#REFUSED_BY_POLICY}. That is what GDR-CORE-1 is met by, with the one exception described below: the
 * JVM-wide {@code SystemReader}, which an embedding application closes by installing
 * {@link RefusingFS#systemReader(org.eclipse.jgit.util.SystemReader)}, the recommended setup. Transport
 * operations contact only remotes the {@link RemotePolicy} admits, checked against the effective URL after
 * {@code insteadOf} rewriting, before anything is sent (GDR-CORE-2). A refused authentication is
 * {@link GitOutcome#CREDENTIALS_INVALID}, every other transport failure {@link GitOutcome#UNREACHABLE} (GDR-CORE-7).
 * <p>
 * Details and log messages render a URL without user information and never carry a credential or a JGit message that
 * may carry one (GDR-NFR-3).
 * <p>
 * The operations change no process-wide JGit state. JGit 7.8.0 reads the user and system configuration of every
 * repository it constructs through the process-wide {@code SystemReader}, whose default locates the system
 * configuration by running {@code git} once per JVM; JGit offers no per-repository configuration source to avoid it.
 * That process is outside what {@link RefusingFS} can refuse, so an embedding application installs
 * {@link RefusingFS#systemReader(org.eclipse.jgit.util.SystemReader)} once at its start, before it constructs a
 * repository; that is the recommended setup, and the one under which no process is started at all.
 * <p>
 * Every transport, the fetch of a clone, a fetch and a push included, runs with the compiled
 * {@link #TRANSPORT_TIMEOUT_SECONDS}.
 *
 * @since 0.1
 */
public final class JGitRepositoryOperations implements RepositoryOperations {

    private static final CuiLogger LOGGER = new CuiLogger(JGitRepositoryOperations.class);

    /** The remote a clone is created with. */
    static final String ORIGIN = Constants.DEFAULT_REMOTE_NAME;

    /**
     * The fetch reference specifications of a clone, unmodifiable: branches, tags and notes are mirrored. A caller that
     * prunes a clone passes them to {@link #fetch(FetchInput)} explicitly, so that the prune deletes only references
     * below the mirrored namespaces whatever fetch reference specifications the clone's configuration holds: JGit
     * 7.8.0 {@code Transport.fetch} falls back to the configured ones only for an empty list, and
     * {@code FetchProcess.deleteStaleTrackingRefs} deletes only references matching the destination of a fetched
     * reference specification.
     *
     * @since 0.1
     */
    public static final List<String> MIRROR_FETCH_REF_SPECS =
            List.of("+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*", "+refs/notes/*:refs/notes/*");

    /**
     * The namespaces a clone mirrors, the destinations a push of a clone may write to: the destination of every
     * {@link #MIRROR_FETCH_REF_SPECS} up to its wildcard, so the two cannot drift apart.
     */
    private static final List<String> MIRRORED_NAMESPACES = MIRROR_FETCH_REF_SPECS.stream()
            .map(spec -> new RefSpec(spec).getDestination())
            .map(destination -> destination.substring(0, destination.indexOf('*')))
            .toList();

    /** The infix of the name of a clone's private staging directory, after the clone's own name. */
    private static final String STAGING_INFIX = ".staging-";

    /**
     * The compiled transport timeout of every transport operation, in seconds: the fetch of
     * {@link #cloneIfMissing(CloneInput)}, {@link #fetch(FetchInput)}, {@link #push(PushInput)} and
     * {@link #fetchCommit(FetchCommitInput)} (JGit 7.8.0 {@code TransportCommand.setTimeout} and
     * {@code Transport.setTimeout}). A remote that stops answering must not block a caller thread forever. The value
     * bounds the connect and every single read, not the total duration of the operation.
     */
    public static final int TRANSPORT_TIMEOUT_SECONDS = 60;

    /** The rendering of a path whose own rendering is blank, such as {@code Path.of("")}, in a detail. */
    static final String BLANK_PATH = "(blank path)";

    /** The rendering of a reference name that is blank in a detail. */
    static final String BLANK_REF = "(blank reference name)";

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

    /** The input field of the lease of {@code push}. */
    private static final String EXPECTED_REMOTE_HEAD = "expectedRemoteHead";

    /** The detail suffix of a repository that is not bare, after its path. */
    private static final String NO_BARE_REPOSITORY = " is no bare repository";

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

    /** The rendering of a value that cannot be shown as a URL without risking its user information (GDR-NFR-3). */
    static final String NOT_A_URL = "(not a URL)";

    /** The detail of a URL that is refused because it cannot be parsed or stored without its user information. */
    private static final String NOT_A_URL_DETAIL = "not a URL";

    /**
     * The largest blob {@link #readFile(ReadFileInput)} reads, in bytes: 16 MiB. The size is asked from the object
     * database before the blob is opened, so a larger blob is never loaded into memory (GDR-CORE-8).
     *
     * @since 0.1
     */
    public static final long READ_FILE_LIMIT_BYTES = 16L * 1024 * 1024;

    /** The detail of a blob larger than {@link #READ_FILE_LIMIT_BYTES}. */
    private static final String LARGER_THAN_LIMIT = "path: larger than the limit of 16 MiB";

    /**
     * The start of a path that is an unparsed authority: a scheme of any case followed by {@code //}, or {@code //}
     * alone (see {@link #display(URIish)}).
     */
    private static final Pattern UNPARSED_AUTHORITY = Pattern.compile("(?:[A-Za-z][A-Za-z0-9+.-]*:)?//");

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
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(root));
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
            Optional<URIish> origin = storedOrigin(new URIish(input.url()));
            if (origin.isEmpty()) {
                return GitResult.of(GitOutcome.REFUSED_BY_POLICY, NOT_A_URL_DETAIL);
            }
            uri = origin.get();
        } catch (URISyntaxException | IllegalArgumentException e) {
            // JGit 7.8.0 URIish parses a port with Integer.parseInt, so a port beyond int range is a
            // NumberFormatException, not a URISyntaxException
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, NOT_A_URL_DETAIL);
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
                | IllegalArgumentException | RemoteRefusedException e) {
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
     * ({@link #MIRROR_FETCH_REF_SPECS}), otherwise a fresh clone would not fetch what was pushed, and it must be a
     * valid reference name once a wildcard is replaced, otherwise the first push would fail to create it.
     *
     * @param spec the caller's push reference specification
     * @return the specification to configure, empty when it is invalid, deletes, matches, leaves the mirrored
     *         namespaces or names no valid destination
     */
    static Optional<RefSpec> pushRefSpec(String spec) {
        try {
            var parsed = new RefSpec(spec, RefSpec.WildcardMode.ALLOW_MISMATCH);
            String source = parsed.getSource();
            if (parsed.isNegative() || parsed.isMatching() || source == null) {
                return Optional.empty();
            }
            String destination = parsed.getDestination() == null ? source : parsed.getDestination();
            // a wildcard stands for a valid name part; a destination JGit would refuse to create, such as one ending
            // in ".lock", is refused here instead of when the first push writes it
            if (MIRRORED_NAMESPACES.stream().noneMatch(destination::startsWith)
                    || !Repository.isValidRefName(destination.replace('*', 'x'))) {
                return Optional.empty();
            }
            return Optional.of(new RefSpec((parsed.isForceUpdate() ? "+" : "") + source + ":" + destination));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The origin a clone is created with, stores in its configuration and fetches from, built from an allowlist of the
     * caller's URL as {@link #display(URIish)} builds its rendering: scheme, host, port and path. User information is
     * dropped, the credentials of a clone are the caller's provider only, and so are query data and a fragment, which
     * JGit 7.8.0 {@link URIish} keeps in its path and would write into the clone's configuration with it. The raw path
     * is kept, so the escaping of the path the transport uses is unchanged.
     * <p>
     * A local path without a scheme and without a host is stored as JGit 7.8.0 {@link URIish} parsed it: its
     * local-file pattern keeps the whole value literally as the path, so a {@code #}, a {@code ?} or a {@code %} is part
     * of a file name there, and cutting at the first {@code #} or {@code ?} or percent-decoding the path through
     * {@link URIish#setRawPath(String)} would store and clone another directory.
     *
     * @param parsed the caller's URL
     * @return the origin; empty for a URL that JGit parsed as a local path although it starts like an authority (see
     *         {@link #display(URIish)}), whose user information is part of the path and cannot be told apart from it,
     *         and empty for an explicit port {@code 0}, which JGit 7.8.0 {@link URIish#setPort(int)} would turn into
     *         the default port, a port the caller did not give
     * @throws URISyntaxException if the raw path without its query data cannot be parsed
     */
    static Optional<URIish> storedOrigin(URIish parsed) throws URISyntaxException {
        if (isUnparsedAuthority(parsed)) {
            return Optional.empty();
        }
        if (parsed.getScheme() == null && parsed.getHost() == null) {
            return Optional.of(parsed);
        }
        // JGit 7.8.0 URIish parses an explicit port of digits only, so 0 is the one explicit port setPort maps to -1
        if (parsed.getPort() == 0) {
            return Optional.empty();
        }
        var origin = new URIish().setScheme(parsed.getScheme()).setHost(parsed.getHost()).setPort(parsed.getPort());
        String rawPath = parsed.getRawPath();
        if (rawPath == null) {
            return Optional.of(origin);
        }
        int end = indexOfQueryOrFragment(rawPath);
        return Optional.of(origin.setRawPath(end < 0 ? rawPath : rawPath.substring(0, end)));
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
     * partial one. The move itself replaces the empty directory the caller prepared (POSIX {@code rename}, the only
     * platform of {@link RefusingFS}) and never a non-empty one, so a move that fails leaves that directory as it
     * was, and a clone published concurrently is never overwritten.
     *
     * @return {@code true} if this attempt published the clone, {@code false} if another one published first
     * @throws IOException if the move failed and the clone's place holds no complete clone
     */
    private boolean publish(Path staging, Path gitDir) throws IOException {
        try {
            Files.move(staging, gitDir, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileSystemException e) {
            // the move onto a clone another attempt published meanwhile fails with ENOTEMPTY, which the JDK reports
            // as a plain FileSystemException (FileAlreadyExistsException is a subclass); any other failure is kept
            if (RepositoryCache.FileKey.isGitRepository(gitDir.toFile(), fs)) {
                return false;
            }
            throw e;
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
            LOGGER.debug("Could not remove the clone staging directory %s: %s", staging, failureName(e));
        }
    }

    private GitResult<RepositoryInfo> openExistingClone(Path gitDir) throws IOException {
        if (!RepositoryCache.FileKey.isGitRepository(gitDir.toFile(), fs)) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, gitDir + " is no repository");
        }
        try (Repository repository = openGitDir(gitDir)) {
            return repository.isBare() ? GitResult.ok(info(repository))
                    : GitResult.of(GitOutcome.NOT_A_REPOSITORY, gitDir + NO_BARE_REPOSITORY);
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
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                    .setTransportConfigCallback(guardedTransport(input.credentials())).call();
            requireMirroredRefs(fetched);
            linkHead(repository, fetched);
            return GitResult.ok(info(repository));
        }
    }

    /**
     * Requires every mirrored reference of a fetch to be written. JGit 7.8.0 {@code FetchProcess} downloads the objects
     * and returns the {@link FetchResult} even when a reference update failed, for example with {@code LOCK_FAILURE}
     * on the packed references lock, so a clone published without that check could lack its references and a retry
     * would open it without fetching again. A remote without references has no updates and is a complete clone.
     *
     * @throws IOException if a reference of the fetch was not written
     */
    private static void requireMirroredRefs(FetchResult fetched) throws IOException {
        for (TrackingRefUpdate update : fetched.getTrackingRefUpdates()) {
            if (notWritten(update).isPresent()) {
                throw new IOException("mirrored reference not written: " + update.getResult());
            }
        }
    }

    /**
     * Checks one reference update of {@link #fetch(FetchInput)} (GDR-CORE-7). JGit 7.8.0 {@code FetchProcess} returns
     * the {@link FetchResult} even when a reference update was not written, so every update is checked:
     * {@code NEW}, {@code FORCED}, {@code FAST_FORWARD} and {@code NO_CHANGE} are written; {@code REJECTED},
     * {@code REJECTED_CURRENT_BRANCH}, {@code REJECTED_MISSING_OBJECT} and {@code REJECTED_OTHER_REASON} end
     * {@link GitOutcome#REJECTED}; {@code LOCK_FAILURE}, {@code IO_FAILURE} and every other result end
     * {@link GitOutcome#FAILED}. The detail names the local reference and the result. An update without a result is
     * {@link GitOutcome#FAILED} as well: JGit 7.8.0 {@code TrackingRefUpdate} holds no result until its command was
     * applied, and nothing says it was written.
     *
     * @return the result of a reference update that was not written, empty when it was written
     */
    static <V> Optional<GitResult<V>> notWritten(TrackingRefUpdate update) {
        var result = update.getResult();
        if (result == null) {
            return Optional.of(GitResult.of(GitOutcome.FAILED, shownRef(update.getLocalName()) + ": no result"));
        }
        String detail = shownRef(update.getLocalName()) + ": " + result;
        return switch (result) {
            case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> Optional.empty();
            case REJECTED, REJECTED_CURRENT_BRANCH, REJECTED_MISSING_OBJECT, REJECTED_OTHER_REASON ->
                Optional.of(GitResult.of(GitOutcome.REJECTED, detail));
            default -> Optional.of(GitResult.of(GitOutcome.FAILED, detail));
        };
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
        Objects.requireNonNull(revision, REVISION);
        try (Repository repository = openRepository(root); RevWalk walk = new RevWalk(repository)) {
            GitResult<ObjectId> start = resolveRevision(repository, revision, REVISION);
            if (!start.isOk()) {
                return withoutValue(start);
            }
            walk.markStart(walk.parseCommit(Objects.requireNonNull(start.value())));
            int limit = Math.max(1, maxCount);
            var commits = new ArrayList<CommitInfo>();
            // walk.next(), not the iterator: JGit 7.8.0 RevWalk.iterator() reads one commit ahead of the one it
            // returns and rethrows a read failure as the unchecked RevWalkException, so it would read past the limit
            // and let a missing commit there escape
            while (commits.size() < limit) {
                RevCommit commit = walk.next();
                if (commit == null) {
                    break;
                }
                commits.add(toInfo(commit));
            }
            return GitResult.ok(List.copyOf(commits));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(root));
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
                    .setRemoveDeletedRefs(input.prune())
                    // JGit 7.8.0 FetchCommand fetches submodules of a working tree on demand, each from its own
                    // remote that the policy check above never saw
                    .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                    .setTransportConfigCallback(guardedTransport(input.credentials()))
                    .call();
            var updates = new ArrayList<RefUpdate>();
            for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
                Optional<GitResult<List<RefUpdate>>> notWritten = notWritten(update);
                if (notWritten.isPresent()) {
                    return notWritten.get();
                }
                updates.add(RefUpdate.builder()
                        .ref(update.getLocalName())
                        .oldId(id(update.getOldObjectId()).orElse(null))
                        .newId(id(update.getNewObjectId()).orElse(null))
                        .status(update.getResult().name().toLowerCase(Locale.ROOT))
                        .build());
            }
            return GitResult.ok(List.copyOf(updates));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(input.root()));
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException
                | IllegalArgumentException | RemoteRefusedException e) {
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
            Optional<ObjectId> local = pushedObject(repository, lookup);
            if (local.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, LOCAL_REF + NOT_FOUND_SUFFIX);
            }
            String expectedRemoteHead = input.expectedRemoteHead();
            if (expectedRemoteHead != null && !ObjectId.isId(expectedRemoteHead)) {
                // JGit 7.8.0 resolves a lease that names nothing to no lease, which would push without one
                return GitResult.of(GitOutcome.NOT_FOUND, EXPECTED_REMOTE_HEAD + NOT_A_FULL_COMMIT_ID);
            }
            // JGit 7.8.0 Transport.findRemoteRefUpdatesFor looks a source up as a reference name first, so the id of
            // a reference is not passed: a reference named like that id would take its place. The full name of the
            // reference is looked up exactly; a full commit id that names no reference is passed as itself.
            String source = lookup.resolution() == GitIdentity.Resolution.REF ? lookup.target().orElseThrow()
                    : local.get().name();
            var command = git.push()
                    .setRemote(input.remote())
                    .setRefSpecs(new RefSpec(source + ":" + input.remoteRef()))
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                    .setTransportConfigCallback(guardedTransport(input.credentials()));
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
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(input.root()));
        } catch (GitAPIException | IOException | JGitInternalException | URISyntaxException
                | IllegalArgumentException | RemoteRefusedException e) {
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
                            : GitResult.of(GitOutcome.NOT_A_REPOSITORY, cache + NO_BARE_REPOSITORY);
                }
            }
            Files.createDirectories(cache);
            Git.init().setBare(true).setGitDir(cache.toFile()).setFs(fs).call().close();
            return GitResult.ok(cache.toRealPath());
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(cache));
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
        } catch (URISyntaxException | IllegalArgumentException e) {
            // a port beyond int range is a NumberFormatException of JGit 7.8.0 URIish, see cloneIfMissing
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, NOT_A_URL_DETAIL);
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
                guard(transport, input.policy());
                if (transport.getURI().getUser() != null || transport.getURI().getPass() != null) {
                    return GitResult.of(GitOutcome.REFUSED_BY_POLICY,
                            display(transport.getURI()) + ": user information in an anonymous fetch");
                }
                // JGit 7.8.0 Transport starts with CredentialsProvider.getDefault(), the process-wide provider
                transport.setCredentialsProvider(null);
                transport.setTimeout(TRANSPORT_TIMEOUT_SECONDS);
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
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(input.cache()));
        } catch (CapabilityRefusedException e) {
            return refusedCapability(e);
        } catch (RemoteRefusedException e) {
            return refused(e);
        } catch (TransportException e) {
            return unservedOrFailed(e, uri, commit);
        } catch (IOException | JGitInternalException e) {
            return transportFailure(e, display(uri), display(uri));
        }
    }

    /**
     * Maps a transport failure of {@code fetchCommit}: a remote that answered by refusing the wanted commit is
     * {@link GitOutcome#UNSERVED}, unless the failure is the refusal of a destination by the policy, and every other
     * failure is mapped by {@link #transportFailure(Exception, String, String)}.
     */
    private static GitResult<String> unservedOrFailed(TransportException failure, URIish uri, ObjectId commit) {
        boolean unserved = cause(failure, RemoteRefusedException.class).isEmpty()
                && isUnservedWant(failure.getMessage());
        return unserved ? GitResult.of(GitOutcome.UNSERVED, display(uri) + " did not serve " + commit.name())
                : transportFailure(failure, display(uri), display(uri));
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
                return readBlob(repository, tree.getObjectId(0));
            }
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, shown(input.cache()));
        } catch (IllegalArgumentException e) {
            return GitResult.of(GitOutcome.NOT_FOUND, "path: not a valid path");
        } catch (IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    /**
     * Reads a blob of at most {@link #READ_FILE_LIMIT_BYTES}. The size is asked first, through
     * {@link ObjectReader#getObjectSize(org.eclipse.jgit.lib.AnyObjectId, int)}, which JGit 7.8.0 reads from the header
     * of a loose object or from the pack entry without inflating the content; the loader is opened only for a blob
     * within the limit, because JGit 7.8.0 {@code Repository.open} reads a blob below its streaming threshold
     * completely into memory before it returns the loader.
     *
     * @return the content, or {@link GitOutcome#REFUSED_BY_POLICY} for a blob above the limit
     * @throws IOException if the blob is missing or cannot be read
     */
    private static GitResult<byte[]> readBlob(Repository repository, ObjectId blobId) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            if (reader.getObjectSize(blobId, Constants.OBJ_BLOB) > READ_FILE_LIMIT_BYTES) {
                return GitResult.of(GitOutcome.REFUSED_BY_POLICY, LARGER_THAN_LIMIT);
            }
            ObjectLoader blob = reader.open(blobId, Constants.OBJ_BLOB);
            try (var content = blob.openStream()) {
                return GitResult.ok(content.readAllBytes());
            }
        }
    }

    /**
     * Points {@code refs/pinned/<id>} at a fetched commit, so it stays reachable. JGit 7.8.0 ignores the destination
     * of a fetch reference specification whose source is an object id ({@code FetchProcess.expandSingle} only wants
     * the object), so the reference is written here. The reference is written without being dereferenced
     * ({@code updateRef(name, true)}), so a symbolic {@code refs/pinned/<id>} in a cache something else modified is
     * replaced by the pin and never moves the branch it points to.
     */
    private static void pin(Repository repository, ObjectId commit) throws IOException {
        var update = repository.updateRef(PINNED_REFS + commit.name(), true);
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
        return GitResult.of(GitOutcome.NOT_A_REPOSITORY, cache + NO_BARE_REPOSITORY);
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
    static <V> Optional<GitResult<V>> refuseUnsupported(GitIdentity.Lookup lookup, String field) {
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
    static Optional<ObjectId> resolveCommit(Repository repository, GitIdentity.Lookup lookup)
            throws IOException {
        Optional<ObjectId> selected = selectedObject(repository, lookup);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(repository.resolve(selected.get().name() + "^{commit}"));
        } catch (MissingObjectException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads the object a supported identity selects. A reference is read by its exact full name, never searched: a
     * reference that no longer exists is not replaced by a prefixed one, such as {@code refs/tags/refs/heads/main} for
     * {@code refs/heads/main} (JGit 7.8.0 {@code Repository.resolve} of a name goes through {@code findRef}, which
     * searches prefixes). A full commit id selects itself.
     *
     * @return the object id, empty when the reference no longer exists
     */
    private static Optional<ObjectId> selectedObject(Repository repository, GitIdentity.Lookup lookup)
            throws IOException {
        String target = lookup.target().orElseThrow();
        if (lookup.resolution() != GitIdentity.Resolution.REF) {
            return Optional.of(ObjectId.fromString(target));
        }
        Ref ref = repository.exactRef(target);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.getObjectId());
    }

    /**
     * The object {@code push} sends: the one the identity selects, present in the repository, and a commit when the
     * caller gave a bare id, since the API accepts a reference or a full commit id only. A reference keeps the object
     * it names, an annotated tag included.
     *
     * @return the object id, empty when it is absent or a raw id names no commit
     */
    private static Optional<ObjectId> pushedObject(Repository repository, GitIdentity.Lookup lookup)
            throws IOException {
        Optional<ObjectId> selected = selectedObject(repository, lookup);
        if (selected.isEmpty() || !repository.getObjectDatabase().has(selected.get())) {
            return Optional.empty();
        }
        boolean rawId = lookup.resolution() == GitIdentity.Resolution.FULL_COMMIT_ID;
        return rawId && !isCommit(repository, selected.get()) ? Optional.empty() : selected;
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
        String remoteRef = shownRef(update.getRemoteName());
        boolean upToDate = update.getStatus() == RemoteRefUpdate.Status.UP_TO_DATE;
        Optional<String> advertisedId = advertised == null ? Optional.empty() : id(advertised.getObjectId());
        if (upToDate && expectedRemoteHead != null
                && !advertisedId.map(expectedRemoteHead::equalsIgnoreCase).orElse(false)) {
            return GitResult.of(GitOutcome.LEASE_REFUSED, remoteRef);
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
                GitResult.of(GitOutcome.REJECTED, remoteRef + ": " + status);
            case REJECTED_REMOTE_CHANGED -> GitResult.of(GitOutcome.LEASE_REFUSED, remoteRef);
            default -> GitResult.of(GitOutcome.FAILED, remoteRef + ": " + status);
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
        Optional<RemoteRefusedException> refusedDestination = cause(failure, RemoteRefusedException.class);
        if (refusedDestination.isPresent()) {
            return refused(refusedDestination.get());
        }
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
        return GitResult.of(GitOutcome.FAILED, target + ": " + failureName(failure));
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

    /**
     * Resolves the revision of a read to its commit (GDR-CORE-11), the one resolution {@code log},
     * {@link RepositoryReader#snapshot(Repository, String)} and
     * {@link RepositoryReader#history(Repository, String, String, int)} share: the revision is classified by
     * {@link GitIdentity#lookup(Repository, String)}, refused by {@link #refuseUnsupported(GitIdentity.Lookup, String)}
     * when it is no supported identity, and resolved by {@link #resolveCommit(Repository, GitIdentity.Lookup)}, an
     * annotated tag peeled to its commit. A full commit id that reaches another commit, such as the id of an annotated
     * tag, is a pin that does not hold.
     *
     * @param repository the repository
     * @param revision   a reference, short or full name, or a full commit id
     * @param field      the name of the input field that carries the revision
     * @return {@link GitOutcome#OK} with the commit; {@link GitOutcome#AMBIGUOUS}, {@link GitOutcome#NOT_FOUND} or
     *         {@link GitOutcome#IDENTITY_MISMATCH} with a detail naming the field
     * @throws IOException if the repository cannot be read
     */
    static GitResult<ObjectId> resolveRevision(Repository repository, String revision, String field)
            throws IOException {
        var lookup = GitIdentity.lookup(repository, revision);
        Optional<GitResult<ObjectId>> refused = refuseUnsupported(lookup, field);
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<ObjectId> resolved = resolveCommit(repository, lookup);
        if (resolved.isEmpty()) {
            return GitResult.of(GitOutcome.NOT_FOUND, field + NOT_FOUND_SUFFIX);
        }
        ObjectId commit = resolved.get();
        if (lookup.resolution() == GitIdentity.Resolution.FULL_COMMIT_ID
                && !commit.name().equalsIgnoreCase(revision)) {
            return GitResult.of(GitOutcome.IDENTITY_MISMATCH, "the pin " + revision + " reached " + commit.name());
        }
        return GitResult.ok(commit);
    }

    /**
     * Carries a non-success over to a result of another value type; a non-success carries no value (GDR-GEN-3).
     *
     * @param nonSuccess the result that did not succeed
     * @param <V>        the value type of the new result
     * @return a result with the same outcome and detail
     * @throws IllegalArgumentException if {@code nonSuccess} is a success
     */
    static <V> GitResult<V> withoutValue(GitResult<?> nonSuccess) {
        return GitResult.of(nonSuccess.outcome(), Objects.requireNonNull(nonSuccess.detail(), "detail"));
    }

    /**
     * Parses a commit by its full id; empty when the id names no commit of the repository.
     * <p>
     * The object is parsed as it is ({@code RevWalk.parseAny}) and must be a commit itself: JGit 7.8.0
     * {@code RevWalk.parseCommit} peels an annotated tag to the commit it points at, so a caller that must act on the
     * addressed commit alone would act on the tagged commit.
     *
     * @param walk the walk that parses the object
     * @param id   the full id of the object
     * @return the commit, or empty when the object is missing or no commit
     * @throws IOException if the repository cannot be read
     */
    static Optional<RevCommit> parseCommit(RevWalk walk, String id) throws IOException {
        try {
            return walk.parseAny(ObjectId.fromString(id)) instanceof RevCommit commit ? Optional.of(commit)
                    : Optional.empty();
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            return Optional.empty();
        }
    }

    /**
     * Converts a parsed commit into its description.
     *
     * @param commit the commit, with its headers parsed
     * @return the id, the parents, author and committer with their times, and the full message
     */
    static CommitInfo toInfo(RevCommit commit) {
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
     * <p>
     * A URL that JGit parsed as a local path although it carries an authority is rendered as {@link #NOT_A_URL}: JGit
     * 7.8.0 {@code URIish} matches its scheme pattern {@code [a-z][a-z0-9+-]+://} in lower case only, so
     * {@code HTTPS://user:secret@host/repo.git} falls through to its local-file pattern, the whole string, user
     * information included, becomes the path, and the host stays {@code null}. Such a value is recognised by a path
     * that starts like an authority, with a scheme and {@code //} or with {@code //} alone.
     *
     * @param uri the parsed URL
     * @return the rendering, never carrying user information
     */
    static String display(URIish uri) {
        if (isUnparsedAuthority(uri)) {
            return NOT_A_URL;
        }
        String path = uri.getPath();
        if (path != null) {
            int end = indexOfQueryOrFragment(path);
            path = end < 0 ? path : path.substring(0, end);
        }
        return new URIish().setScheme(uri.getScheme()).setHost(uri.getHost()).setPort(uri.getPort()).setPath(path)
                .toString();
    }

    /**
     * Tells whether JGit parsed a URL as a local path although it starts like an authority: no host, and a path that
     * starts with a scheme and {@code //} or with {@code //} alone (see {@link #display(URIish)}).
     */
    private static boolean isUnparsedAuthority(URIish uri) {
        String path = uri.getPath();
        return uri.getHost() == null && path != null && UNPARSED_AUTHORITY.matcher(path).lookingAt();
    }

    private static int indexOfQueryOrFragment(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        if (query < 0) {
            return fragment;
        }
        return fragment < 0 ? query : Math.min(query, fragment);
    }

    /**
     * The configuration of every transport a named-remote command opens, applied before it connects.
     * <p>
     * It checks the transport's actual destination against the policy and refuses it with a
     * {@link RemoteRefusedException}: JGit 7.8.0 {@code FetchCommand} and {@code PushCommand} resolve the remote name
     * again, and {@code FileRepository.getConfig()} reloads a configuration saved after
     * {@link #checkRemote(Repository, String, boolean)} read it, so only the transport's own URL is the destination the
     * command uses. It then sets exactly the caller's credentials, none when the caller supplies none: JGit 7.8.0
     * {@code TransportCommand} starts with {@code CredentialsProvider.getDefault()} and keeps it when given
     * {@code null}.
     */
    private TransportConfigCallback guardedTransport(@Nullable CredentialsProvider credentials) {
        return transport -> {
            guard(transport, remotePolicy);
            transport.setCredentialsProvider(credentials);
        };
    }

    /**
     * Binds a transport to a policy before it connects, for the named remotes and for {@code fetchCommit} alike: its
     * destination must be admitted, and an HTTP transport opens every connection, a redirect target included, through
     * a {@link PolicyCheckedConnectionFactory}.
     * <p>
     * JGit 7.8.0 {@code TransportHttp} follows a redirect of the first request ({@code http.followRedirects},
     * default {@code initial}) and its {@code isValidRedirect} accepts any host of the same scheme, so an admitted
     * remote could send the request on to a host the policy refuses. The check sits in the connection factory of this
     * one transport ({@code TransportHttp.setHttpConnectionFactory}), not in the process-wide
     * {@code HttpTransport.setConnectionFactory}, and no repository or user configuration can switch it off.
     *
     * @throws RemoteRefusedException if the destination is outside the policy
     */
    static void guard(Transport transport, RemotePolicy policy) {
        if (!policy.permits(transport.getURI())) {
            throw new RemoteRefusedException(transport.getURI(), false);
        }
        if (transport instanceof TransportHttp http) {
            http.setHttpConnectionFactory(new PolicyCheckedConnectionFactory(http.getHttpConnectionFactory(), policy));
        }
    }

    /**
     * Opens an HTTP connection only to a URL the policy admits, and refuses every other one before the request is
     * sent; the connections themselves come from the factory it wraps.
     */
    static final class PolicyCheckedConnectionFactory implements HttpConnectionFactory2 {

        private final HttpConnectionFactory delegate;
        private final RemotePolicy policy;

        PolicyCheckedConnectionFactory(HttpConnectionFactory delegate, RemotePolicy policy) {
            this.delegate = delegate;
            this.policy = policy;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            return delegate.create(checked(url));
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            return delegate.create(checked(url), proxy);
        }

        @Override
        public GitSession newSession() {
            if (delegate instanceof HttpConnectionFactory2 sessions) {
                return sessions.newSession();
            }
            return new GitSession() {

                @Override
                public HttpConnection configure(HttpConnection connection, boolean sslVerify) {
                    return connection;
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
        }

        private URL checked(URL url) {
            var uri = new URIish(url);
            if (!policy.permits(uri)) {
                throw new RemoteRefusedException(uri, true);
            }
            return url;
        }
    }

    /** Aborts a command whose transport would contact a destination outside the policy, before it connects. */
    static final class RemoteRefusedException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** The refused destination. */
        private final URIish uri;

        /** Whether the destination is an HTTP connection other than the transport's own URL, such as a redirect. */
        private final boolean redirect;

        RemoteRefusedException(URIish uri, boolean redirect) {
            super("remote outside the remote policy", null, false, false);
            this.uri = uri;
            this.redirect = redirect;
        }

        URIish uri() {
            return uri;
        }

        boolean redirect() {
            return redirect;
        }
    }

    /** The refusal of a destination: a redirect is named by its host only (GDR-NFR-3). */
    private static <V> GitResult<V> refused(RemoteRefusedException refusal) {
        if (!refusal.redirect()) {
            return refusedRemote(refusal.uri());
        }
        String host = String.valueOf(refusal.uri().getHost());
        LOGGER.warn(WARN.REMOTE_REFUSED, host);
        return GitResult.of(GitOutcome.REFUSED_BY_POLICY, "redirect to " + host + " is outside the remote policy");
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
    static <V> GitResult<V> failure(Exception e) {
        return GitResult.of(GitOutcome.FAILED, failureName(e));
    }

    /**
     * The name of a failure's class for a detail, never blank: the simple name, or the binary name for an anonymous
     * class, whose simple name is empty.
     */
    static String failureName(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * Renders a path of the caller for a detail, never blank: {@code Path.of("")} renders empty, so a blank rendering
     * is replaced by {@link #BLANK_PATH}, and {@link GitResult#of(GitOutcome, String)} never meets a blank detail.
     */
    static String shown(Path path) {
        String rendered = path.toString();
        return rendered.isBlank() ? BLANK_PATH : rendered;
    }

    /** Renders a reference name for a detail, never blank: a blank name is replaced by {@link #BLANK_REF}. */
    static String shownRef(@Nullable String name) {
        return name == null || name.isBlank() ? BLANK_REF : name;
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
