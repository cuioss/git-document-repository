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
package de.cuioss.git.document.forms;

import java.util.Objects;


import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.Ulid;

/**
 * The keys and running numbers of new documents and entries (GDR-FORMS-9).
 * <p>
 * A new document of a per-document kind is stored under a ULID from the first draft on: {@link #newKey(Kind)} returns
 * its key, whose id names its working branch, its tags and its data directory. A new entry of a numbered kind starts
 * under a ULID too, the key of its draft; it receives its readable running number on its first save, inside the
 * per-attempt step of that save, from the counter file {@code <collection>/.counter} of the attempt's tip: the next
 * counter formatted by {@link #number(Numbering, long)} names the entry file, and the advanced counter is written in the
 * same commit. Two creators that read the same counter race on the collection branch; the compare-and-swap of the store
 * refuses the loser's push, and its re-applied attempt reads the winner's counter and takes the next number, so no two
 * entries receive the same number. {@link #isAssigned(Kind, DocumentKey)} tells a key that carries its running number
 * from the ULID key of a draft. {@link Ulid#next()} is the only source of an id.
 *
 * @since 0.1
 */
public final class RunningNumbers {

    private RunningNumbers() {
    }

    /**
     * Creates the key of a new document of a per-document kind, or of the draft of a new entry of a numbered kind.
     *
     * @param kind the kind
     * @return the key in the collection of the kind, with a fresh ULID as its id
     * @throws NullPointerException     if {@code kind} is {@code null}
     * @throws IllegalArgumentException if the collection of the kind is no valid collection name of the store
     */
    public static DocumentKey newKey(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return new DocumentKey(collection(kind), Ulid.next());
    }

    /**
     * Formats a running number: the prefix of the numbering followed by the counter, zero-padded to its digits.
     *
     * @param numbering the numbering of the kind
     * @param counter   the counter, from 1
     * @return {@link FormsOutcome#OK} with the number, for example {@code R-042} for the prefix {@code R-}, 3 digits and
     *         the counter 42; the passed-on {@link StoreOutcome#MALFORMED} for a counter that needs more digits than the
     *         numbering has, its detail naming the limit
     * @throws NullPointerException     if {@code numbering} is {@code null}
     * @throws IllegalArgumentException if {@code counter} is less than 1
     */
    public static FormsResult<String> number(Numbering numbering, long counter) {
        Objects.requireNonNull(numbering, "numbering");
        if (counter < 1) {
            throw new IllegalArgumentException("counter: a running number starts at 1");
        }
        String digits = Long.toString(counter);
        if (digits.length() > numbering.digits()) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                    DocumentLayout.COUNTER_FILE + ": a counter of more than " + numbering.digits()
                            + " digits, the limit of the numbering"));
        }
        return FormsResult.ok(numbering.prefix() + "0".repeat(numbering.digits() - digits.length()) + digits);
    }

    /**
     * Tells whether the key of an entry carries its running number: its id is the prefix of the numbering followed by
     * exactly its digits. A key that does not, such as the ULID key of a draft from {@link #newKey(Kind)}, receives its
     * number on its first save.
     *
     * @param kind the numbered kind
     * @param key  the key of an entry of the kind
     * @return whether the id of the key is a running number of the kind
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the kind is not numbered
     */
    public static boolean isAssigned(Kind kind, DocumentKey key) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Numbering numbering = kind.numbering();
        if (numbering == null) {
            throw new IllegalArgumentException("kind: a per-document kind has no running numbers");
        }
        String id = key.id();
        if (id.length() != numbering.prefix().length() + numbering.digits() || !id.startsWith(numbering.prefix())) {
            return false;
        }
        return id.substring(numbering.prefix().length()).chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** The collection of a kind, as the store names it. */
    private static DocumentCollection collection(Kind kind) {
        return new DocumentCollection(kind.collection(),
                kind.isNumbered() ? DocumentCollection.Kind.NUMBERED : DocumentCollection.Kind.PER_DOCUMENT);
    }
}
