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

import java.util.Arrays;
import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * One edit of a tree written in core (GDR-CORE-4): it sets a path to content or deletes it.
 * <p>
 * The path is repository-relative, its segments separated by {@code /}, without a leading or trailing {@code /}. The
 * edit does not check it; {@link InCoreWriter} validates every path before it writes anything and reports an invalid
 * one as {@link GitOutcome#NOT_FOUND}.
 *
 * @since 0.1
 */
public sealed interface TreeEdit permits TreeEdit.Put, TreeEdit.Delete {

    /**
     * Returns the path the edit applies to.
     *
     * @return the repository-relative path
     */
    String path();

    /**
     * Sets a path to content, as a regular file (mode {@code 100644}). A file or a directory at the path is replaced.
     * <p>
     * The content is copied on construction and on access, so the edit cannot be changed afterwards. Two edits are equal
     * when their paths and their contents are equal; {@link #toString()} renders the path and the length of the content,
     * never the content itself.
     *
     * @param path    the repository-relative path
     * @param content the content of the file
     * @since 0.1
     */
    record Put(String path, byte[] content) implements TreeEdit {

        /**
         * Checks and copies the components.
         *
         * @param path    the repository-relative path
         * @param content the content of the file
         * @throws NullPointerException if a component is {@code null}
         */
        public Put {
            Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content").clone();
        }

        /**
         * Returns a copy of the content.
         *
         * @return the content of the file
         */
        @Override
        public byte[] content() {
            return content.clone();
        }

        /**
         * Compares the path and the content by value.
         *
         * @param other the object to compare with
         * @return whether {@code other} is a {@link Put} with the same path and the same content
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Put(String otherPath, byte[] otherContent) && path.equals(otherPath)
                    && Arrays.equals(content, otherContent);
        }

        /**
         * Computes the hash code from the path and the content.
         *
         * @return the hash code
         */
        @Override
        public int hashCode() {
            return 31 * path.hashCode() + Arrays.hashCode(content);
        }

        /**
         * Renders the path and the length of the content, never the content.
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "Put[path=" + path + ", length=" + content.length + "]";
        }
    }

    /**
     * Deletes the file at a path. A path that holds no file leaves the tree unchanged.
     *
     * @param path the repository-relative path
     * @since 0.1
     */
    record Delete(String path) implements TreeEdit {

        /**
         * Checks the component.
         *
         * @param path the repository-relative path
         * @throws NullPointerException if {@code path} is {@code null}
         */
        public Delete {
            Objects.requireNonNull(path, "path");
        }
    }
}
