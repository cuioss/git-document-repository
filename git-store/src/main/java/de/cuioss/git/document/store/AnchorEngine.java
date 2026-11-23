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
package de.cuioss.git.document.store;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * The anchoring engine of one bare clone (GDR-STORE-15, GDR-STORE-16, GDR-STORE-17): without an active provider it
 * does nothing; with one, a due check decides which subjects are anchored and when, and asks the provider only to
 * obtain proofs.
 * <p>
 * A due check runs under the anchoring lock of the clone, so two due checks never request a proof for the same subject,
 * and in three phases:
 * <ol>
 * <li>under the shared side of the maintenance lock and the transport lock: a fetch that mirrors the remote and
 * prunes; the push of the tag of a checkpoint and of the note of a proof an earlier due check kept because its push
 * failed; a new checkpoint when one is due ({@link Checkpoints#due}); and the pending subjects, derived from Git state
 * only: the commit of every release tag (unless {@code anchor.releases} is {@code false}) and every checkpoint without a
 * note in the notes reference of the provider as the fetch mirrored it, oldest commit time first;</li>
 * <li>without any lock of the clone: the provider obtains the proof of each pending subject in turn, with the commit time
 * of the subject and the newest proof as {@code previous}: the note the newest notes commit added
 * ({@link AnchorNotes#newest}), or the proof obtained just before in the same due check;</li>
 * <li>under the shared side and the transport lock again, per proof: the proof is kept under its local-only reference,
 * its note written and pushed with lease right after ({@link AnchorNotes#publish}), unless the subject got a note from
 * the remote meanwhile.</li>
 * </ol>
 * The order of the locks is the anchoring lock, the shared side of the maintenance lock, the transport lock; a save, a
 * release and a fetch take no anchoring lock, so no cycle arises, and a slow provider holds no lock a save needs. A
 * failure of the provider or of a push leaves the subject pending, ends the due check, is logged ({@code GIT_STORE-111})
 * with the alias and the outcome only, and is retried by the next due check; it never reaches the caller of a save, a
 * release or a fetch, which hand the due check to an executor.
 */
final class AnchorEngine {

    private static final CuiLogger LOGGER = new CuiLogger(AnchorEngine.class);

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    private final ExplicitSave.Clone clone;
    private final @Nullable AnchorProvider provider;
    private final StoreConfiguration.Anchor configuration;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final ExplicitSave.Committing committing;
    private final Checkpoints checkpoints;
    private final @Nullable AnchorNotes notes;

    /**
     * Creates the engine of a clone.
     *
     * @param clone         the bare clone
     * @param provider      the active anchor provider; {@code null} when {@code anchor.provider} is absent
     * @param configuration the section {@code anchor}
     * @param operations    the repository operations of {@code git-core}, for fetch and push
     * @param user          the technical user, committer and holder of the transport credentials
     * @param committing    the identities, the signing seam and the clock of the checkpoint and notes commits
     * @param attempts      the attempts of a compare-and-swap ({@code save.max-attempts}), at least 1
     * @throws NullPointerException if an argument other than {@code provider} is {@code null}
     */
    AnchorEngine(ExplicitSave.Clone clone, @Nullable AnchorProvider provider,
            StoreConfiguration.Anchor configuration, RepositoryOperations operations, TechnicalUser user,
            ExplicitSave.Committing committing, int attempts) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.provider = provider;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.committing = Objects.requireNonNull(committing, "committing");
        this.checkpoints = new Checkpoints(clone, operations, user, committing);
        this.notes = provider == null ? null
                : new AnchorNotes(clone, provider.id(), operations, user, committing, attempts);
    }

    /**
     * Tells whether a provider is active.
     *
     * @return whether the engine anchors anything
     */
    boolean active() {
        return provider != null;
    }

    /**
     * Runs one due check.
     *
     * @return {@link StoreOutcome#OK} with the subjects anchored and those left pending; or the non-success that ended
     *         the due check before any subject was asked for, for example the passed-on outcome of the fetch; a failure of
     *         the provider or a push is reported through the pending subjects, not as a non-success
     */
    StoreResult<StoreWriter.Anchoring> dueCheck() {
        AnchorProvider active = provider;
        AnchorNotes proofs = notes;
        if (active == null || proofs == null) {
            return StoreResult.ok(new StoreWriter.Anchoring(List.of(), List.of()));
        }
        RepositoryGuard guard = clone.guard();
        return guard.anchoring(() -> run(active, proofs, guard));
    }

    private StoreResult<StoreWriter.Anchoring> run(AnchorProvider active, AnchorNotes proofs, RepositoryGuard guard) {
        List<String> anchored = new ArrayList<>();
        StoreResult<Plan> planned = guard.shared(() -> guard.transport(() -> prepare(proofs, anchored)));
        if (!planned.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(), StoreResult.name(planned.outcome()));
            return new StoreResult<>(planned.outcome(), null, planned.detail());
        }
        Plan plan = Objects.requireNonNull(planned.value());
        byte[] previous = plan.previous();
        List<Subject> pending = plan.pending();
        int index = 0;
        for (; index < pending.size(); index++) {
            Subject subject = pending.get(index);
            var obtained = obtain(active, subject, previous);
            if (!obtained.isOk()) {
                LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(),
                        StoreResult.name(obtained.outcome()));
                break;
            }
            byte[] proof = Objects.requireNonNull(obtained.value());
            var published = guard.shared(() -> guard.transport(() -> publish(proofs, subject, proof)));
            if (!published.isOk()) {
                LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(),
                        StoreResult.name(published.outcome()));
                break;
            }
            if (Boolean.TRUE.equals(published.value())) {
                anchored.add(subject.id().name());
                LOGGER.info(GitStoreLogMessages.INFO.SUBJECT_ANCHORED, clone.alias(), subject.id().name());
                previous = proof;
            }
        }
        List<String> left = pending.subList(index, pending.size()).stream().map(subject -> subject.id().name())
                .toList();
        return StoreResult.ok(new StoreWriter.Anchoring(anchored, left));
    }

    /** Asks the provider for one proof; an exception of the provider leaves the subject pending like a refusal. */
    private StoreResult<byte[]> obtain(AnchorProvider active, Subject subject, byte[] previous) {
        try {
            var obtained = active.obtain(clone.alias(), subject.id(), subject.time(), previous.clone());
            if (obtained.isOk() && obtained.value() == null) {
                return StoreResult.of(StoreOutcome.REFUSED, "anchor: provider returned no proof");
            }
            return obtained;
        }
        // a provider is pluggable code; its failure must never end the due check of the store (GDR-STORE-15)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException e) {
            LOGGER.debug("The anchor provider threw %s", e.getClass().getSimpleName());
            return StoreResult.of(StoreOutcome.REFUSED, "anchor: provider failed");
        }
    }

    /**
     * Phase one, under the transport lock: fetch, push what an earlier due check kept, make a due checkpoint, and
     * derive the pending subjects with the newest proof.
     */
    private StoreResult<Plan> prepare(AnchorNotes proofs, List<String> anchored) {
        var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(user.credentials())
                .prune(true).build());
        if (!fetched.isOk()) {
            return StoreResult.passOn(fetched);
        }
        try {
            Checkpoints.Latest latest = checkpoints.latest();
            List<ObjectId> repaired = checkpoints.repair(latest);
            if (!repaired.isEmpty()) {
                latest = checkpoints.latest();
            }
            byte[] listed = Checkpoints.refsFile(clone.repository());
            if (checkpoints.due(latest, listed, committing.clock().instant(), configuration.checkpointInterval())) {
                var created = checkpoints.create(latest, listed);
                LOGGER.debug("A due checkpoint was made: %s", StoreResult.name(created.outcome()));
            }
            Map<ObjectId, Instant> subjects = subjects();
            Set<ObjectId> annotated = proofs.annotated();
            if (!pushKept(proofs, subjects, annotated, anchored)) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, proofs.notesRef() + ": a kept proof was not pushed");
            }
            annotated = proofs.annotated();
            List<Subject> pending = new ArrayList<>();
            for (Map.Entry<ObjectId, Instant> subject : subjects.entrySet()) {
                if (!annotated.contains(subject.getKey())) {
                    pending.add(new Subject(subject.getKey(), subject.getValue()));
                }
            }
            pending.sort(Comparator.comparing(Subject::time).thenComparing(subject -> subject.id().name()));
            byte[] previous = proofs.newest().flatMap(proofs::proof).orElseGet(() -> new byte[0]);
            return StoreResult.ok(new Plan(pending, previous));
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("I/O failure of the due check: %s", type);
            return StoreResult.of(StoreOutcome.INCOMPLETE, "anchor: " + type);
        }
    }

    /**
     * Pushes the note of every proof an earlier due check kept, oldest subject first, and drops a kept proof whose
     * subject is no subject of the anchoring any more or got a note from the remote meanwhile.
     *
     * @return whether every kept proof of a pending subject was pushed
     */
    private boolean pushKept(AnchorNotes proofs, Map<ObjectId, Instant> subjects, Set<ObjectId> annotated,
            List<String> anchored) throws IOException {
        Map<ObjectId, byte[]> kept = proofs.kept();
        List<ObjectId> order = new ArrayList<>(kept.keySet());
        order.sort(Comparator.comparing((ObjectId id) -> subjects.getOrDefault(id, Instant.MIN))
                .thenComparing(ObjectId::name));
        for (ObjectId subject : order) {
            if (!subjects.containsKey(subject) || annotated.contains(subject)) {
                proofs.drop(subject);
                continue;
            }
            var published = proofs.publish(subject, kept.get(subject));
            if (!published.isOk()) {
                return false;
            }
            anchored.add(subject.name());
            LOGGER.info(GitStoreLogMessages.INFO.SUBJECT_ANCHORED, clone.alias(), subject.name());
        }
        return true;
    }

    /**
     * Phase three, under the transport lock: keeps the proof, then writes and pushes its note, unless the subject got a
     * note from the remote since phase one.
     *
     * @return {@link StoreOutcome#OK} with {@code true} when the note is on the remote now, {@code false} when the
     *         remote had one already; otherwise the non-success of the write or the push, the proof staying kept
     */
    private StoreResult<Boolean> publish(AnchorNotes proofs, Subject subject, byte[] proof) {
        try {
            if (proofs.annotated().contains(subject.id())) {
                return StoreResult.ok(false);
            }
            proofs.keep(subject.id(), proof);
            var published = proofs.publish(subject.id(), proof);
            return published.isOk() ? StoreResult.ok(true)
                    : new StoreResult<>(published.outcome(), null, published.detail());
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("I/O failure of the publication of a proof: %s", type);
            return StoreResult.of(StoreOutcome.INCOMPLETE, "anchor: " + type);
        }
    }

    /**
     * The subjects of the anchoring with their commit time: the commit of every release tag unless
     * {@code anchor.releases} is {@code false}, and every checkpoint. A tag that points at no commit is no subject.
     */
    private Map<ObjectId, Instant> subjects() throws IOException {
        Map<ObjectId, Instant> subjects = new LinkedHashMap<>();
        Repository repository = clone.repository();
        try (RevWalk walk = new RevWalk(repository)) {
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(StoreReferences.TAGS)) {
                boolean subject = StoreReferences.checkpointNumber(ref.getName()).isPresent()
                        || configuration.releases() && StoreReferences.isReleaseTag(ref.getName());
                @Nullable ObjectId target = ref.getObjectId();
                if (subject && target != null) {
                    commit(walk, target).ifPresent(commit -> subjects.put(commit.copy(),
                            commit.getCommitterIdent().getWhenAsInstant()));
                }
            }
        }
        return subjects;
    }

    private static Optional<RevCommit> commit(RevWalk walk, ObjectId target) throws IOException {
        try {
            return Optional.of(walk.parseCommit(target));
        } catch (IncorrectObjectTypeException e) {
            return Optional.empty();
        }
    }

    /**
     * A subject of the anchoring.
     *
     * @param id   the anchored commit
     * @param time its commit time
     */
    private record Subject(ObjectId id, Instant time) {
    }

    /**
     * What phase one derived.
     *
     * @param pending  the pending subjects, oldest first
     * @param previous the newest proof, empty for none
     */
    private record Plan(List<Subject> pending, byte[] previous) {
    }
}
