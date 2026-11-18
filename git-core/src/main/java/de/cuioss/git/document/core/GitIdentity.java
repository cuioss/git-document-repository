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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


import lombok.experimental.UtilityClass;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The identity rules of commits and references (GDR-CORE-11): a pinned commit is an identity that is checked, never a
 * name that is looked up.
 * <p>
 * A commit is passed and compared as its full 40-character object id; {@link #abbreviate(Repository, AnyObjectId)}
 * produces an abbreviation for display, but an abbreviated id is never resolved as an identity. A branch is addressed
 * as {@code refs/heads/<name>}, a tag as {@code refs/tags/<name>} and a remote branch as
 * {@code refs/remotes/<remote>/<name>}.
 * <p>
 * A name that resolves both as a reference and as an object id, or as two references, is ambiguous and is reported
 * as {@link GitOutcome#AMBIGUOUS}, with the {@link #candidates(Repository, String)} in the detail. Only a
 * {@linkplain Lookup#isSupported() supported identity} is resolved: exactly one reference or a full commit id. JGit
 * 7.8.0 {@code Repository.resolve} accepts an abbreviation of two or more digits and a revision expression on an
 * abbreviated base ({@code <prefix>~0}), so everything else is refused before it reaches JGit.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitIdentity {

    /** The name of the repository argument, reported by the null checks. */
    private static final String REPOSITORY = "repository";

    /** A full commit id: 40 hexadecimal digits, in either case. */
    private static final Pattern FULL_COMMIT_ID = Pattern.compile("[0-9a-f]{40}", Pattern.CASE_INSENSITIVE);

    /** A prefix that may abbreviate a commit id: 4 to 39 hexadecimal digits, in either case. */
    private static final Pattern ABBREVIATED_COMMIT_ID =
            Pattern.compile("[0-9a-f]{4,39}", Pattern.CASE_INSENSITIVE);

    /**
     * How a revision string is addressed.
     *
     * @since 0.1
     */
    public enum Resolution {
        /** A full 40-character commit id. */
        FULL_COMMIT_ID,
        /** Exactly one reference whose short name is the string. */
        REF,
        /** A hexadecimal prefix shorter than 40 characters that the repository resolves. */
        ABBREVIATED_COMMIT_ID,
        /** The string names both a reference and a full object id. */
        REF_AND_COMMIT_ID,
        /** The string names two or more references. */
        TWO_REFS,
        /** Neither a reference nor an object of the repository. */
        UNKNOWN
    }

    /**
     * Tells whether a revision string is a full commit id.
     *
     * @param value a revision string
     * @return whether it is a full 40-character commit id
     */
    public static boolean isFullCommitId(@Nullable String value) {
        return value != null && FULL_COMMIT_ID.matcher(value).matches();
    }

    /**
     * Tells whether a revision string has the form of an abbreviated commit id.
     *
     * @param value a revision string
     * @return whether it is a hexadecimal prefix of 4 to 39 digits
     */
    public static boolean isAbbreviatedCommitId(@Nullable String value) {
        return value != null && ABBREVIATED_COMMIT_ID.matcher(value).matches();
    }

    /**
     * Returns the full reference of a branch.
     *
     * @param branch the branch name, short or full
     * @return the full reference of the branch, {@code refs/heads/<branch>}
     */
    public static String branchRef(String branch) {
        Objects.requireNonNull(branch, "branch");
        return branch.startsWith(Constants.R_HEADS) ? branch : Constants.R_HEADS + branch;
    }

    /**
     * Returns the full reference of a tag.
     *
     * @param tag the tag name, short or full
     * @return the full reference of the tag, {@code refs/tags/<tag>}
     */
    public static String tagRef(String tag) {
        Objects.requireNonNull(tag, "tag");
        return tag.startsWith(Constants.R_TAGS) ? tag : Constants.R_TAGS + tag;
    }

    /**
     * Returns the full reference of a remote-tracking branch.
     *
     * @param remote the remote name
     * @param branch the branch name
     * @return the full remote-tracking reference, {@code refs/remotes/<remote>/<branch>}
     */
    public static String remoteBranchRef(String remote, String branch) {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(branch, "branch");
        return Constants.R_REMOTES + remote + "/" + branch;
    }

    /**
     * Produces the shortest unique abbreviation of an object id in the repository, through JGit's
     * {@link ObjectReader#abbreviate(AnyObjectId)} (checked against JGit 7.8.0). The abbreviation is for display; it
     * is never resolved back as an identity.
     *
     * @param repository the repository the abbreviation must be unique in
     * @param id         the object id
     * @return the abbreviation, at least 7 hexadecimal digits
     * @throws IOException if the object database cannot be read
     */
    public static String abbreviate(Repository repository, AnyObjectId id) throws IOException {
        Objects.requireNonNull(repository, REPOSITORY);
        Objects.requireNonNull(id, "id");
        try (ObjectReader reader = repository.newObjectReader()) {
            return reader.abbreviate(id).name();
        }
    }

    /**
     * How a revision string is addressed, together with what it may name, both taken from one read of the reference
     * database, so the candidates always belong to the resolution even while another writer changes the references.
     *
     * @param resolution how the string is addressed
     * @param candidates the full name of every reference the string names, in the order of the reference database,
     *                   followed by the string itself when it is a full commit id; empty when it names nothing
     * @since 0.1
     */
    public record Lookup(Resolution resolution, List<String> candidates) {

        /**
         * Checks and copies the components.
         *
         * @param resolution how the string is addressed
         * @param candidates what the string may name
         * @throws NullPointerException     if a component is {@code null}
         * @throws IllegalArgumentException if the resolution is a supported identity, a reference or a full commit
         *                                  id, and the candidates are not exactly one
         */
        public Lookup {
            Objects.requireNonNull(resolution, "resolution");
            candidates = List.copyOf(candidates);
            if ((resolution == Resolution.REF || resolution == Resolution.FULL_COMMIT_ID) && candidates.size() != 1) {
                throw new IllegalArgumentException("a supported identity names exactly one candidate");
            }
        }

        /**
         * Tells whether the string is a supported identity: exactly one reference, or a full commit id that names no
         * reference. Everything else, an abbreviated id and a revision expression such as {@code main~1} included, is
         * not resolved.
         *
         * @return whether the string may be resolved
         */
        public boolean isSupported() {
            return resolution == Resolution.REF || resolution == Resolution.FULL_COMMIT_ID;
        }

        /**
         * Returns the unambiguous name to resolve a supported identity by: the full reference name of a reference,
         * the commit id itself for a full commit id. Resolving by it never reaches JGit's abbreviation parser.
         *
         * @return the name to resolve, empty when the string is no supported identity
         */
        public Optional<String> target() {
            return isSupported() ? Optional.of(candidates.getFirst()) : Optional.empty();
        }
    }

    /**
     * Classifies a revision string against the repository and lists its candidates, from one read of the reference
     * database. A string names a reference when it is the reference's full name or its short name; {@code HEAD} is
     * such a reference.
     *
     * @param repository the repository
     * @param name       the revision string
     * @return how the string is addressed and what it may name
     * @throws IOException if the reference database cannot be read
     */
    public static Lookup lookup(Repository repository, String name) throws IOException {
        Objects.requireNonNull(repository, REPOSITORY);
        Objects.requireNonNull(name, "name");
        List<String> refs = matchingRefs(repository, name);
        // A full 40-character id is an identity by its syntax: a reference of the same name is ambiguous whether or
        // not the object is present in this clone (a shallow clone or a GC must not turn the pin back into a name).
        var fullCommitId = isFullCommitId(name);
        List<String> candidates = new ArrayList<>(refs);
        if (fullCommitId) {
            candidates.add(name);
        }
        return new Lookup(classify(repository, name, refs.size(), fullCommitId), candidates);
    }

    private static Resolution classify(Repository repository, String name, int refCount, boolean fullCommitId)
            throws IOException {
        if (refCount > 1) {
            return Resolution.TWO_REFS;
        }
        if (refCount == 1 && fullCommitId) {
            return Resolution.REF_AND_COMMIT_ID;
        }
        if (refCount == 1) {
            return Resolution.REF;
        }
        if (fullCommitId) {
            return Resolution.FULL_COMMIT_ID;
        }
        if (isAbbreviatedCommitId(name) && resolvesAbbreviated(repository, name)) {
            return Resolution.ABBREVIATED_COMMIT_ID;
        }
        return Resolution.UNKNOWN;
    }

    /**
     * Classifies a revision string against the repository.
     *
     * @param repository the repository
     * @param name       the revision string
     * @return how the string is addressed
     * @throws IOException if the reference database cannot be read
     */
    public static Resolution resolution(Repository repository, String name) throws IOException {
        return lookup(repository, name).resolution();
    }

    /**
     * Lists what a revision string may name: the full name of every reference whose full or short name is the string,
     * in the order of the reference database, followed by the string itself when it is a full commit id. The list is
     * the detail of {@link GitOutcome#AMBIGUOUS}; a caller that also needs the resolution takes both from
     * {@link #lookup(Repository, String)}.
     *
     * @param repository the repository
     * @param name       the revision string
     * @return the candidates, empty when the string names nothing
     * @throws IOException if the reference database cannot be read
     */
    public static List<String> candidates(Repository repository, String name) throws IOException {
        return lookup(repository, name).candidates();
    }

    /**
     * Tells whether a resolution is ambiguous.
     *
     * @param resolution a resolution
     * @return whether the name is ambiguous and must be refused
     */
    public static boolean isAmbiguous(Resolution resolution) {
        return resolution == Resolution.REF_AND_COMMIT_ID || resolution == Resolution.TWO_REFS;
    }

    private static List<String> matchingRefs(Repository repository, String name) throws IOException {
        return repository.getRefDatabase().getRefs().stream()
                .map(Ref::getName)
                .filter(refName -> name.equals(refName) || name.equals(Repository.shortenRefName(refName)))
                .distinct()
                .toList();
    }

    /**
     * Whether the repository resolves the prefix. An ambiguous prefix is an abbreviated id too, not an I/O failure.
     */
    private static boolean resolvesAbbreviated(Repository repository, String name) throws IOException {
        try {
            return repository.resolve(name) != null;
        } catch (AmbiguousObjectException ambiguous) {
            return true;
        }
    }
}
