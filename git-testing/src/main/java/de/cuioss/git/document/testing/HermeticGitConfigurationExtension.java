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
package de.cuioss.git.document.testing;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Installs, for each test method, a JGit {@link SystemReader} that delegates to the reader installed before but opens
 * an empty user, system and JGit configuration, and restores the previous reader after the test method (checked against
 * JGit 7.8.0: {@code SystemReader.Delegate} forwards every other call, {@code SystemReader.setInstance} replaces the
 * JVM-wide reader). It is registered by {@link HermeticGitConfiguration}, whose documentation states its limits.
 *
 * @since 0.1
 */
public final class HermeticGitConfigurationExtension implements BeforeEachCallback, AfterEachCallback {

    /** The namespace under which the reader installed before a test is stored until it is restored. */
    private static final Namespace NAMESPACE = Namespace.create(HermeticGitConfigurationExtension.class);

    /** The store key of the reader installed before a test. */
    private static final String PREVIOUS = "previous";

    /**
     * Creates the extension; JUnit Jupiter instantiates it for {@link HermeticGitConfiguration}.
     */
    public HermeticGitConfigurationExtension() {
        // stateless: the previous reader is kept in the extension context's store
    }

    /**
     * Stores the current reader and installs the isolating one in its place.
     *
     * @param context the context of the test method
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        var previous = SystemReader.getInstance();
        context.getStore(NAMESPACE).put(PREVIOUS, previous);
        SystemReader.setInstance(new HermeticReader(previous));
    }

    /**
     * Restores the reader that was installed before the test method.
     *
     * @param context the context of the test method
     */
    @Override
    public void afterEach(ExtensionContext context) {
        SystemReader.setInstance(context.getStore(NAMESPACE).remove(PREVIOUS, SystemReader.class));
    }

    /** A reader that opens an empty user, system and JGit configuration and delegates every other call. */
    static final class HermeticReader extends SystemReader.Delegate {

        HermeticReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return empty(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return empty(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return empty(parent, fs);
        }

        /**
         * An empty configuration backed by no file, as JGit 7.8.0 opens the system configuration when
         * {@code GIT_CONFIG_NOSYSTEM} is set: loading reads nothing and it is never outdated.
         */
        private static FileBasedConfig empty(Config parent, FS fs) {
            return new FileBasedConfig(parent, null, fs) {

                @Override
                public void load() {
                    // nothing to read: the configuration stays empty
                }

                @Override
                public boolean isOutdated() {
                    return false;
                }
            };
        }
    }
}
