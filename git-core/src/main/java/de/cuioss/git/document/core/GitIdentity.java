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
 * as {@link GitOutcome#AMBIGUOUS}, with the {@link #candidates(Repository, String)} in the detail.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitIdentity {

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
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(id, "id");
        try (ObjectReader reader = repository.newObjectReader()) {
            return reader.abbreviate(id).name();
        }
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
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(name, "name");
        int refCount = matchingRefs(repository, name).size();
        // A full 40-character id is an identity by its syntax: a reference of the same name is ambiguous whether or
        // not the object is present in this clone (a shallow clone or a GC must not turn the pin back into a name).
        var fullCommitId = isFullCommitId(name);
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
     * Lists what a revision string may name: the full name of every reference whose short name is the string, in the
     * order of the reference database, followed by the string itself when it is a full commit id. The list is the
     * detail of {@link GitOutcome#AMBIGUOUS}.
     *
     * @param repository the repository
     * @param name       the revision string
     * @return the candidates, empty when the string names nothing
     * @throws IOException if the reference database cannot be read
     */
    public static List<String> candidates(Repository repository, String name) throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(name, "name");
        List<String> candidates = new ArrayList<>(matchingRefs(repository, name));
        if (isFullCommitId(name)) {
            candidates.add(name);
        }
        return List.copyOf(candidates);
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
                .filter(refName -> name.equals(Repository.shortenRefName(refName)))
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
