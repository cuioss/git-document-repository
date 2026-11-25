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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The reference layout of a repository of the store (GDR-STORE-2): the released state, the working and collection
 * branches, the release tags (GDR-STORE-6), the drafts, the applied drafts and the push reference specifications.
 * <p>
 * Every reference name is built from segments that contain no character git-check-ref-format(1) forbids, no dot and no
 * slash: the collection name ({@code [a-z0-9-]+}, {@link DocumentCollection}), the id ({@link DocumentKey}) and the
 * pseudonym ({@code [A-Za-z0-9-]+}). A collection is either per-document or numbered, never both, because JGit refuses a
 * reference nested below an existing one and one that would contain an existing one ({@code RefDatabase
 * .isNameConflicting}, JGit 7.8.0), so {@code refs/heads/<collection>} and {@code refs/heads/<collection>/<id>} cannot
 * exist side by side; {@link #collections(List)} refuses a name declared twice.
 *
 * @since 0.1
 */
public final class StoreReferences {

    /** The branch of the released state, {@code refs/heads/main}. */
    public static final String MAIN = "refs/heads/main";

    /** The prefix of every draft reference, {@code refs/drafts/}; never pushed (ADR-0003). */
    public static final String DRAFTS = "refs/drafts/";

    /**
     * The prefix of every applied-draft reference, {@code refs/applied/}. The reference
     * {@code refs/applied/<pseudonym>/<collection>/<id>} points at the draft commit a save last applied while a newer
     * draft was kept, so the next save applies only what the draft changed since then. It lives in the clone only:
     * never pushed, never fetched.
     */
    public static final String APPLIED = "refs/applied/";

    /**
     * The push reference specifications of the bare clone: the branches, the tags and the notes, each to the same name
     * on the remote. They never carry {@code refs/drafts/*}, so no draft reaches the remote (ADR-0003).
     */
    public static final List<String> PUSH_REF_SPECS =
            List.of("refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*", "refs/notes/*:refs/notes/*");

    /**
     * The namespaces a checkpoint lists, derived from the source side of {@link #PUSH_REF_SPECS} without its wildcard:
     * the namespaces the clone pushes, which are those {@code JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS} mirrors.
     */
    private static final List<String> LISTED_NAMESPACES = PUSH_REF_SPECS.stream()
            .map(spec -> spec.substring(0, spec.indexOf(':') - 1)).toList();

    /** The prefix of every tag, {@code refs/tags/}. */
    public static final String TAGS = "refs/tags/";

    /**
     * The prefix of every checkpoint tag, {@code refs/tags/checkpoints/} (GDR-STORE-16): {@code refs/tags/checkpoints/<n>}
     * with {@code n} from 1, never moved or deleted.
     */
    public static final String CHECKPOINTS = "refs/tags/checkpoints/";

    /**
     * The prefix of every notes reference of anchor proofs, {@code refs/notes/anchors/} (GDR-STORE-17):
     * {@code refs/notes/anchors/<provider>} per anchor provider.
     */
    public static final String ANCHOR_NOTES = "refs/notes/anchors/";

    /**
     * The prefix of the local-only references of the anchoring, {@code refs/anchoring/}: a proof or a checkpoint commit
     * whose push failed is kept here until the next due check pushes it. Outside {@code refs/heads/},
     * {@code refs/tags/} and {@code refs/notes/}, so it is never pushed, never fetched and never pruned by a fetch.
     */
    static final String ANCHORING = "refs/anchoring/";

    /** The prefix of every branch; the one home of the constant in {@code git-store}. */
    static final String HEADS = "refs/heads/";

    /** The remote of every bare clone of the store; the one home of the constant in {@code git-store}. */
    static final String ORIGIN = "origin";

    /** The number of a checkpoint, from 1 to 999999999 without leading zeros. */
    private static final Pattern CHECKPOINT_NUMBER = Pattern.compile("[1-9]\\d{0,8}");

    /**
     * The structure of a release tag, {@code refs/tags/<collection>/<id>/} followed by the last segment, which
     * {@link #RELEASE_VERSION} parses.
     */
    private static final Pattern RELEASE_TAG = Pattern.compile("refs/tags/[a-z0-9-]+/[A-Za-z0-9-]+/([^/]*)");

    /** The form of a pseudonym. */
    private static final Pattern PSEUDONYM = Pattern.compile("[A-Za-z0-9-]+");

    /** The last segment of a release tag, {@code v<n>} with {@code n} from 1 to 999999999 without leading zeros. */
    private static final Pattern RELEASE_VERSION = Pattern.compile("v([1-9]\\d{0,8})");

    /** The detail of a refused pseudonym; it never carries the pseudonym. */
    static final String INVALID_PSEUDONYM = "pseudonym: invalid";

    private StoreReferences() {
    }

    /**
     * Returns the branch a save of the document or entry targets.
     *
     * @param key the key of the document or entry
     * @return {@code refs/heads/<collection>/<id>} for a per-document collection, {@code refs/heads/<collection>} for a
     *         numbered collection, and {@value #MAIN} for content without release
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public static String target(DocumentKey key) {
        Objects.requireNonNull(key, "key");
        var collection = key.collection();
        return switch (collection.kind()) {
            case PER_DOCUMENT -> HEADS + collection.name() + "/" + key.id();
            case NUMBERED -> HEADS + collection.name();
            case MAIN -> MAIN;
        };
    }

    /**
     * Returns the prefix of every release tag of a document or entry (GDR-STORE-6).
     *
     * @param key the key of the document or entry
     * @return {@code refs/tags/<collection>/<id>/}, ending in {@code /}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public static String releaseTags(DocumentKey key) {
        Objects.requireNonNull(key, "key");
        return TAGS + key.collection().name() + "/" + key.id() + "/";
    }

    /**
     * Returns the release tag of one version of a document or entry (GDR-STORE-6).
     *
     * @param key     the key of the document or entry
     * @param version the version {@code n}, counted from 1
     * @return {@code refs/tags/<collection>/<id>/v<n>}
     * @throws NullPointerException     if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code version} is less than 1
     */
    public static String releaseTag(DocumentKey key, int version) {
        if (version < 1) {
            throw new IllegalArgumentException("version: less than 1");
        }
        return releaseTags(key) + "v" + version;
    }

    /**
     * Parses the version of a release tag of a document or entry (GDR-STORE-6).
     *
     * @param key the key of the document or entry
     * @param ref the full name of a reference
     * @return the version {@code n} of {@code refs/tags/<collection>/<id>/v<n>}, or empty for any other reference,
     *         a version with a leading zero or one above 999999999 included
     * @throws NullPointerException if an argument is {@code null}
     */
    public static OptionalInt releaseVersion(DocumentKey key, String ref) {
        Objects.requireNonNull(ref, "ref");
        String prefix = releaseTags(key);
        if (!ref.startsWith(prefix)) {
            return OptionalInt.empty();
        }
        return version(ref.substring(prefix.length()));
    }

    /** Parses the last segment of a release tag, {@code v<n>}; empty for any other segment. */
    private static OptionalInt version(String segment) {
        var version = RELEASE_VERSION.matcher(segment);
        return version.matches() ? OptionalInt.of(Integer.parseInt(version.group(1))) : OptionalInt.empty();
    }

    /**
     * Tells whether a reference is a release tag of some document or entry, {@code refs/tags/<collection>/<id>/v<n>}
     * (GDR-STORE-6); a checkpoint tag is none.
     *
     * @param ref the full name of a reference
     * @return whether it has the form of a release tag
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public static boolean isReleaseTag(String ref) {
        var tag = RELEASE_TAG.matcher(Objects.requireNonNull(ref, "ref"));
        return tag.matches() && version(tag.group(1)).isPresent();
    }

    /**
     * Returns the tag of a checkpoint (GDR-STORE-16).
     *
     * @param number the number {@code n} of the checkpoint, counted from 1
     * @return {@code refs/tags/checkpoints/<n>}
     * @throws IllegalArgumentException if {@code number} is less than 1
     */
    public static String checkpoint(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("number: less than 1");
        }
        return CHECKPOINTS + number;
    }

    /**
     * Parses the number of a checkpoint tag (GDR-STORE-16).
     *
     * @param ref the full name of a reference
     * @return the number {@code n} of {@code refs/tags/checkpoints/<n>}, or empty for any other reference, a number
     *         with a leading zero or one above 999999999 included
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public static OptionalInt checkpointNumber(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (!ref.startsWith(CHECKPOINTS)) {
            return OptionalInt.empty();
        }
        String number = ref.substring(CHECKPOINTS.length());
        return CHECKPOINT_NUMBER.matcher(number).matches() ? OptionalInt.of(Integer.parseInt(number))
                : OptionalInt.empty();
    }

    /**
     * Returns the notes reference of the proofs of an anchor provider (GDR-STORE-17).
     *
     * @param provider the id of the provider, of the form {@code [a-z0-9-]+}
     * @return {@code refs/notes/anchors/<provider>}
     * @throws NullPointerException if {@code provider} is {@code null}
     */
    public static String anchorNotes(String provider) {
        return ANCHOR_NOTES + Objects.requireNonNull(provider, "provider");
    }

    /**
     * Returns the prefix of the local-only references of proofs whose note was not pushed yet.
     *
     * @param provider the id of the provider
     * @return {@code refs/anchoring/<provider>/proofs/}, followed by the full id of the anchored commit; each such
     *         reference points at a blob holding the id of the notes commit the proof extends (the zero id for none), a
     *         line feed and the proof
     */
    static String retainedProofs(String provider) {
        return ANCHORING + provider + "/proofs/";
    }

    /**
     * Returns the prefix of the local-only references of checkpoint commits whose tag was not pushed yet.
     *
     * @return {@code refs/anchoring/checkpoints/}, followed by the number of the checkpoint
     */
    static String retainedCheckpoints() {
        return ANCHORING + "checkpoints/";
    }

    /**
     * Tells whether a reference is listed by a checkpoint: a reference in a namespace of {@link #PUSH_REF_SPECS} (a
     * branch, a tag or a notes reference), but neither a checkpoint tag nor a notes reference of anchor proofs, which
     * the anchoring itself moves (GDR-STORE-16).
     *
     * @param ref the full name of a reference
     * @return whether a checkpoint lists it
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public static boolean listedByCheckpoint(String ref) {
        Objects.requireNonNull(ref, "ref");
        return LISTED_NAMESPACES.stream().anyMatch(ref::startsWith) && !ref.startsWith(CHECKPOINTS)
                && !ref.startsWith(ANCHOR_NOTES);
    }

    /**
     * Returns the draft reference of a user for a document or entry.
     *
     * @param pseudonym the pseudonym of the user
     * @param key       the key of the document or entry
     * @return {@link StoreOutcome#OK} with {@code refs/drafts/<pseudonym>/<collection>/<id>}, or
     *         {@link StoreOutcome#MALFORMED} with the detail {@code pseudonym: invalid} for a pseudonym outside
     *         {@code [A-Za-z0-9-]+}; the detail never carries the pseudonym
     * @throws NullPointerException if {@code pseudonym} or {@code key} is {@code null}
     */
    public static StoreResult<String> draft(String pseudonym, DocumentKey key) {
        Objects.requireNonNull(pseudonym, "pseudonym");
        Objects.requireNonNull(key, "key");
        if (!isPseudonym(pseudonym)) {
            return StoreResult.of(StoreOutcome.MALFORMED, INVALID_PSEUDONYM);
        }
        return StoreResult.ok(drafts(pseudonym) + key.collection().name() + "/" + key.id());
    }

    /**
     * Returns the prefix of every draft reference of a user, {@code refs/drafts/<pseudonym>/}.
     *
     * @param pseudonym the pseudonym, already checked by {@link #isPseudonym(String)}
     * @return the prefix, ending in {@code /}
     */
    static String drafts(String pseudonym) {
        return DRAFTS + pseudonym + "/";
    }

    /**
     * Returns the applied-draft reference that belongs to a draft reference.
     *
     * @param draftRef a draft reference, below {@value #DRAFTS}
     * @return the reference of the same name below {@value #APPLIED}
     * @throws IllegalArgumentException if {@code draftRef} is no draft reference
     */
    static String applied(String draftRef) {
        if (!draftRef.startsWith(DRAFTS)) {
            throw new IllegalArgumentException("not a draft reference");
        }
        return APPLIED + draftRef.substring(DRAFTS.length());
    }

    /**
     * Returns the draft reference an applied-draft reference belongs to.
     *
     * @param appliedRef an applied-draft reference, below {@value #APPLIED}
     * @return the reference of the same name below {@value #DRAFTS}
     * @throws IllegalArgumentException if {@code appliedRef} is no applied-draft reference
     */
    static String draftOf(String appliedRef) {
        if (!appliedRef.startsWith(APPLIED)) {
            throw new IllegalArgumentException("not an applied-draft reference");
        }
        return DRAFTS + appliedRef.substring(APPLIED.length());
    }

    /**
     * Tells whether a text is a valid pseudonym segment, {@code [A-Za-z0-9-]+}.
     *
     * @param pseudonym the text
     * @return whether it is a valid pseudonym
     */
    static boolean isPseudonym(String pseudonym) {
        return PSEUDONYM.matcher(pseudonym).matches();
    }

    /**
     * Checks the declared collections of a repository.
     *
     * @param collections the declared collections, in declaration order
     * @return {@link StoreOutcome#OK} with an unmodifiable copy of the collections, or {@link StoreOutcome#MALFORMED}
     *         with the detail {@code collection <name>: declared twice} for the first name declared more than once,
     *         whatever the kinds, since a name cannot be both per-document and numbered
     * @throws NullPointerException if {@code collections} or one of its elements is {@code null}
     */
    public static StoreResult<List<DocumentCollection>> collections(List<DocumentCollection> collections) {
        Objects.requireNonNull(collections, "collections");
        Set<String> names = new HashSet<>();
        for (DocumentCollection collection : collections) {
            Objects.requireNonNull(collection, "collection");
            if (!names.add(collection.name())) {
                return StoreResult.of(StoreOutcome.MALFORMED, "collection " + collection.name() + ": declared twice");
            }
        }
        return StoreResult.ok(List.copyOf(collections));
    }
}
