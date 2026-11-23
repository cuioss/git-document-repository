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
 * failed; a new checkpoint when one is due ({@link Checkpoints#due}) and no kept checkpoint waits for its tag push; and
 * the pending subjects, derived from Git state only: the commit of every release tag (unless {@code anchor.releases}
 * is {@code false}) and every checkpoint without a note in the notes reference of the provider as the fetch mirrored
 * it, oldest commit time first;</li>
 * <li>without any lock of the clone: the provider obtains the proof of each pending subject in turn, with the commit time
 * of the subject and the newest proof as {@code previous}: the note the notes tip added ({@link AnchorNotes#current}),
 * or the proof published just before in the same due check;</li>
 * <li>under the shared side and the transport lock again, per proof: the proof is kept under its local-only reference,
 * its note written onto the notes tip the proof extends and pushed with that tip as the lease
 * ({@link AnchorNotes#publish}). A tip that moved meanwhile, for example by a fetch that brought the note of another
 * clone, ends the due check before the next subject, so no proof forks the chain; the next due check asks again with
 * the newest proof.</li>
 * </ol>
 * The order of the locks is the anchoring lock, the shared side of the maintenance lock, the transport lock; a save, a
 * release and a fetch take no anchoring lock, so no cycle arises, and a slow provider holds no lock a save needs. A
 * failure of the provider or of a push leaves the subject pending, ends the due check, is logged ({@code GIT_STORE-111})
 * with the alias and the outcome only, and is retried by the next due check; it never reaches the caller of a save, a
 * release or a fetch, which hand the due check to an executor. The reference updates of the fetch of a due check are
 * handed to the events of the clone ({@link StoreEvents}) once every lock was released, as those of every other fetch.
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
    private final StoreEvents events;
    private final Checkpoints checkpoints;
    private final @Nullable AnchorNotes notes;

    /**
     * The anchor provider of a clone with the section {@code anchor}.
     *
     * @param provider      the active anchor provider; {@code null} when {@code anchor.provider} is absent
     * @param configuration the section {@code anchor}
     */
    record Selection(@Nullable AnchorProvider provider, StoreConfiguration.Anchor configuration) {

        /**
         * Checks the section.
         *
         * @param provider      the provider
         * @param configuration the section
         * @throws NullPointerException if {@code configuration} is {@code null}
         */
        Selection {
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    /**
     * Creates the engine of a clone.
     *
     * @param clone      the bare clone
     * @param selection  the active anchor provider and the section {@code anchor}
     * @param operations the repository operations of {@code git-core}, for fetch and push
     * @param user       the technical user, committer and holder of the transport credentials
     * @param committing the identities, the signing seam and the clock of the checkpoint and notes commits
     * @param attempts   the attempts of a compare-and-swap ({@code save.max-attempts}), at least 1
     * @param events     the notification seam of the clone, for the reference updates of the fetch of a due check
     * @throws NullPointerException if an argument is {@code null}
     */
    AnchorEngine(ExplicitSave.Clone clone, Selection selection, RepositoryOperations operations, TechnicalUser user,
            ExplicitSave.Committing committing, int attempts, StoreEvents events) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.provider = Objects.requireNonNull(selection, "selection").provider();
        this.configuration = selection.configuration();
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.committing = Objects.requireNonNull(committing, "committing");
        this.events = Objects.requireNonNull(events, "events");
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
        List<StoreEvents.Update> updates = new ArrayList<>();
        var result = guard.anchoring(() -> run(active, proofs, guard, updates));
        events.emit(updates);
        return result;
    }

    private StoreResult<StoreWriter.Anchoring> run(AnchorProvider active, AnchorNotes proofs, RepositoryGuard guard,
            List<StoreEvents.Update> updates) {
        List<String> anchored = new ArrayList<>();
        StoreResult<Plan> planned = guard.shared(() -> guard.transport(() -> prepare(proofs, anchored, updates)));
        if (!planned.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(), StoreResult.name(planned.outcome()));
            return new StoreResult<>(planned.outcome(), null, planned.detail());
        }
        Plan plan = Objects.requireNonNull(planned.value());
        AnchorNotes.Tip tip = plan.tip();
        List<Subject> pending = plan.pending();
        int index = 0;
        for (; index < pending.size(); index++) {
            Subject subject = pending.get(index);
            Optional<AnchorNotes.Tip> next = anchor(active, proofs, guard, subject, tip);
            if (next.isEmpty()) {
                break;
            }
            tip = next.get();
            anchored.add(subject.id().name());
        }
        List<String> left = pending.subList(index, pending.size()).stream().map(subject -> subject.id().name())
                .toList();
        return StoreResult.ok(new StoreWriter.Anchoring(anchored, left));
    }

    /**
     * Phases two and three for one subject: obtains its proof with the proof of the tip as {@code previous}, then
     * publishes it onto that tip.
     *
     * @return the tip the next proof extends, the new notes commit with this proof; empty when the provider failed, the
     *         publication failed or the tip moved, which ends the due check with the subject pending
     */
    private Optional<AnchorNotes.Tip> anchor(AnchorProvider active, AnchorNotes proofs, RepositoryGuard guard,
            Subject subject, AnchorNotes.Tip tip) {
        var obtained = obtain(active, subject, tip.proof());
        if (!obtained.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(), StoreResult.name(obtained.outcome()));
            return Optional.empty();
        }
        var proof = new AnchorNotes.Kept(tip.commit(), Objects.requireNonNull(obtained.value()));
        var published = guard.shared(() -> guard.transport(() -> publish(proofs, subject, proof)));
        if (!published.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(),
                    StoreResult.name(published.outcome()));
            return Optional.empty();
        }
        LOGGER.info(GitStoreLogMessages.INFO.SUBJECT_ANCHORED, clone.alias(), subject.id().name());
        return Optional.of(new AnchorNotes.Tip(published.value(), proof.proof()));
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
     * derive the pending subjects with the notes tip.
     */
    private StoreResult<Plan> prepare(AnchorNotes proofs, List<String> anchored, List<StoreEvents.Update> updates) {
        var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(user.credentials())
                .prune(true).build());
        if (!fetched.isOk()) {
            return StoreResult.passOn(fetched);
        }
        StoreEvents.fetched(fetched.value(), updates);
        try {
            Checkpoints.Standing standing = checkpoint();
            if (standing == Checkpoints.Standing.STRANDED) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, "anchor: a checkpoint tag that was not pushed remains");
            }
            if (standing == Checkpoints.Standing.UNTARGETED) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, "anchor: the newest checkpoint tag points at no commit");
            }
            Map<ObjectId, Instant> subjects = subjects();
            if (!pushKept(proofs, subjects, proofs.annotated(), anchored)) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, proofs.notesRef() + ": a kept proof was not pushed");
            }
            Set<ObjectId> annotated = proofs.annotated();
            List<Subject> pending = new ArrayList<>();
            for (Map.Entry<ObjectId, Instant> subject : subjects.entrySet()) {
                if (!annotated.contains(subject.getKey())) {
                    pending.add(new Subject(subject.getKey(), subject.getValue()));
                }
            }
            pending.sort(Comparator.comparing(Subject::time).thenComparing(subject -> subject.id().name()));
            return StoreResult.ok(new Plan(pending, proofs.current()));
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("I/O failure of the due check: %s", type);
            return StoreResult.of(StoreOutcome.INCOMPLETE, "anchor: " + type);
        }
    }

    /**
     * Repairs the checkpoints an earlier due check kept, then makes a due checkpoint unless a kept one still waits for
     * its tag push. A newest checkpoint tag that points at no commit blocks both: nothing is repaired or made, since
     * only the first checkpoint has no parent.
     *
     * @return how the checkpoints stand
     */
    private Checkpoints.Standing checkpoint() throws IOException {
        Checkpoints.Latest latest = checkpoints.latest();
        if (latest.untargeted()) {
            LOGGER.debug("The newest checkpoint tag points at no commit; no checkpoint is made");
            return Checkpoints.Standing.UNTARGETED;
        }
        Checkpoints.Repair repair = checkpoints.repair(latest);
        if (repair.standing() != Checkpoints.Standing.CURRENT) {
            return repair.standing();
        }
        if (!repair.pushed().isEmpty()) {
            latest = checkpoints.latest();
        }
        byte[] listed = Checkpoints.refsFile(clone.repository());
        if (!checkpoints.due(latest, listed, committing.clock().instant(), configuration.checkpointInterval())) {
            return Checkpoints.Standing.CURRENT;
        }
        var created = checkpoints.create(latest, listed);
        LOGGER.debug("A due checkpoint was made: %s", StoreResult.name(created.outcome()));
        return created.isOk() ? Objects.requireNonNull(created.value()) : Checkpoints.Standing.CURRENT;
    }

    /**
     * Pushes the note of every proof an earlier due check kept, oldest subject first, and drops a kept proof whose
     * subject is no subject of the anchoring any more, got a note from the remote meanwhile, or extends another tip than
     * the current one, so a new proof is requested for it.
     *
     * @return whether every kept proof that is still valid was pushed
     */
    private boolean pushKept(AnchorNotes proofs, Map<ObjectId, Instant> subjects, Set<ObjectId> annotated,
            List<String> anchored) throws IOException {
        Map<ObjectId, AnchorNotes.Kept> kept = proofs.kept();
        List<ObjectId> order = new ArrayList<>(kept.keySet());
        order.sort(Comparator.comparing((ObjectId id) -> subjects.getOrDefault(id, Instant.MIN))
                .thenComparing(ObjectId::name));
        for (ObjectId subject : order) {
            AnchorNotes.Kept proof = Objects.requireNonNull(kept.get(subject));
            if (!subjects.containsKey(subject) || annotated.contains(subject)
                    || !Objects.equals(proof.basis(), proofs.tip())) {
                proofs.drop(subject);
                continue;
            }
            var published = proofs.publish(subject, proof);
            if (!published.isOk()) {
                return false;
            }
            anchored.add(subject.name());
            LOGGER.info(GitStoreLogMessages.INFO.SUBJECT_ANCHORED, clone.alias(), subject.name());
        }
        return true;
    }

    /**
     * Phase three, under the transport lock: keeps the proof, then writes its note onto the tip it extends and pushes
     * it.
     *
     * @return {@link StoreOutcome#OK} with the new notes commit; otherwise the non-success of the publication, for
     *         example {@link StoreOutcome#INCOMPLETE} for a tip that moved since phase one or a failed push
     */
    private StoreResult<ObjectId> publish(AnchorNotes proofs, Subject subject, AnchorNotes.Kept proof) {
        try {
            return proofs.publish(subject.id(), proof);
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
     * @param pending the pending subjects, oldest first
     * @param tip     the notes tip the first proof extends, with the newest proof
     */
    private record Plan(List<Subject> pending, AnchorNotes.Tip tip) {
    }
}
