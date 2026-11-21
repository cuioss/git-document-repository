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

    /** The prefix of every tag, {@code refs/tags/}. */
    public static final String TAGS = "refs/tags/";

    /** The prefix of every branch. */
    private static final String HEADS = "refs/heads/";

    /** The form of a pseudonym. */
    private static final Pattern PSEUDONYM = Pattern.compile("[A-Za-z0-9-]+");

    /** The last segment of a release tag, {@code v<n>} with {@code n} from 1 to 999999999 without leading zeros. */
    private static final Pattern RELEASE_VERSION = Pattern.compile("v([1-9][0-9]{0,8})");

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
        var version = RELEASE_VERSION.matcher(ref.substring(prefix.length()));
        return version.matches() ? OptionalInt.of(Integer.parseInt(version.group(1))) : OptionalInt.empty();
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
