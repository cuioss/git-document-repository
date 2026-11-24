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
 * The RFC 3161 anchor provider of the store: timestamp tokens from an external timestamp authority and their
 * verification.
 * <p>
 * The module requires {@code de.cuioss.git.document.store} transitively, because it implements the store's
 * {@code AnchorProvider} and its public API returns the store's {@code StoreResult}; through it the module also reads
 * {@code de.cuioss.java.tools}, whose {@code LogRecord} the public holder {@code GitTimestampLogMessages} exposes. It
 * requires {@code java.net.http} for the transport to the timestamp authority, and {@code org.jspecify} statically for
 * the null-safety annotations. It adds no third-party dependency: the DER/CMS reading and writing of RFC 3161 is its
 * own, on the JDK.
 * <p>
 * It provides the anchor provider {@code rfc3161} ({@code Rfc3161AnchorProvider}) to {@link java.util.ServiceLoader}.
 * The instance {@code ServiceLoader} creates through the public no-argument constructor is unconfigured: it never
 * reaches the network, its {@code obtain} refuses and its {@code verify} reports an invalid signature, each naming the
 * missing {@code timestamp} section. An application builds the configured provider with
 * {@code Rfc3161AnchorProvider.from} from its configuration and hands it to {@code StoreProviders.select} as a
 * candidate in place of the loaded one.
 */
module de.cuioss.git.document.timestamp {
    exports de.cuioss.git.document.timestamp;

    requires transitive de.cuioss.git.document.store;
    requires java.net.http;
    requires static org.jspecify;

    provides de.cuioss.git.document.store.AnchorProvider with de.cuioss.git.document.timestamp.Rfc3161AnchorProvider;
}
