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
/**
 * The RFC 3161 anchor provider {@code rfc3161} of the store: it obtains a timestamp token for an object id from an
 * external timestamp authority and verifies a stored token against the object id and the configured trust; and the
 * log messages of {@code git-timestamp} ({@link de.cuioss.git.document.timestamp.GitTimestampLogMessages}).
 * <p>
 * The package decides nothing about which commits are anchored and handles no notes or pushes; that is the anchoring
 * engine of {@code git-store}.
 */
@NullMarked
package de.cuioss.git.document.timestamp;

import org.jspecify.annotations.NullMarked;
