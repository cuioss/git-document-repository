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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.git.document.testing.LocalRepositoryExtensionTest.ExtensionContextResolver;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The isolation from the git configuration of the host. A reader standing in for the host opens a user configuration
 * from a temporary file holding {@code http.followRedirects=false}; the extension is driven directly around it, so the
 * test controls what the host would set. The class runs isolated because the reader is global.
 */
@Isolated
@DisplayName("Hermetic git configuration")
class HermeticGitConfigurationTest {

    private static final String SECTION = "http";

    private static final String NAME = "followRedirects";

    @RegisterExtension
    static final ParameterResolver EXTENSION_CONTEXT = new ExtensionContextResolver();

    private final HermeticGitConfigurationExtension extension = new HermeticGitConfigurationExtension();

    @Test
    @DisplayName("Should hide a user setting of the host while the test runs and restore the previous reader after it")
    void shouldHideUserSettingAndRestorePreviousReader(ExtensionContext context, @TempDir Path home)
            throws Exception {
        var userConfiguration = home.resolve("gitconfig");
        Files.writeString(userConfiguration, "[" + SECTION + "]\n\t" + NAME + " = false\n");
        var original = SystemReader.getInstance();
        var host = new HostUserConfiguration(original, userConfiguration.toFile());
        SystemReader.setInstance(host);
        try {
            assertEquals("false", userSetting(), "The reader of the host opens the user setting");
            var hostRepository = LocalRepositoryExtension.createBareRepository(LocalRepository.class);
            try (hostRepository) {
                assertEquals("false", hostRepository.repository().getConfig().getString(SECTION, null, NAME),
                        "A repository opened on the host inherits the user setting");
            }

            extension.beforeEach(context);
            try {
                var hermetic = SystemReader.getInstance();
                var isolatedRepository = LocalRepositoryExtension.createBareRepository(LocalRepository.class);
                try (isolatedRepository) {
                    assertAll("inside the isolation",
                            () -> assertInstanceOf(HermeticGitConfigurationExtension.HermeticReader.class, hermetic,
                                    "The isolating reader is installed"),
                            () -> assertNull(userSetting(), "The user setting of the host is hidden"),
                            () -> assertTrue(hermetic.getUserConfig().getSections().isEmpty(),
                                    "The user configuration is empty"),
                            () -> assertTrue(hermetic.getSystemConfig().getSections().isEmpty(),
                                    "The system configuration is empty"),
                            () -> assertTrue(hermetic.getJGitConfig().getSections().isEmpty(),
                                    "The JGit configuration is empty"),
                            () -> assertNull(isolatedRepository.repository().getConfig()
                                            .getString(SECTION, null, NAME),
                                    "A repository opened inside the isolation does not see the user setting"),
                            () -> assertEquals(host.getHostname(), hermetic.getHostname(),
                                    "Every other call is delegated to the previous reader"));
                }
            } finally {
                extension.afterEach(context);
            }

            assertAll("after the isolation",
                    () -> assertSame(host, SystemReader.getInstance(), "The previous reader is restored"),
                    () -> assertEquals("false", userSetting(), "The user setting of the host is read again"));
        } finally {
            SystemReader.setInstance(original);
        }
    }

    private static String userSetting() throws Exception {
        return SystemReader.getInstance().getUserConfig().getString(SECTION, null, NAME);
    }

    /**
     * A test class under the annotation: the isolating reader is installed for its {@code @BeforeEach} methods and
     * its test methods.
     */
    @Nested
    @HermeticGitConfiguration
    @DisplayName("Test class annotated with @HermeticGitConfiguration")
    class Annotated {

        private SystemReader readerBeforeEach;

        @BeforeEach
        void captureReader() {
            readerBeforeEach = SystemReader.getInstance();
        }

        @Test
        @DisplayName("Should run the test and its @BeforeEach methods with empty user, system and JGit configurations")
        void shouldRunWithEmptyConfigurations() {
            var reader = SystemReader.getInstance();

            assertAll("annotated test",
                    () -> assertInstanceOf(HermeticGitConfigurationExtension.HermeticReader.class, reader,
                            "The isolating reader is installed for the test method"),
                    () -> assertSame(reader, readerBeforeEach,
                            "The @BeforeEach method ran with the same isolating reader"),
                    () -> assertTrue(reader.getUserConfig().getSections().isEmpty(),
                            "The user configuration is empty"),
                    () -> assertTrue(reader.getSystemConfig().getSections().isEmpty(),
                            "The system configuration is empty"),
                    () -> assertTrue(reader.getJGitConfig().getSections().isEmpty(),
                            "The JGit configuration is empty"));
        }
    }

    /** A reader standing in for the host: its user configuration is the given file. */
    static final class HostUserConfiguration extends SystemReader.Delegate {

        private final File userConfiguration;

        HostUserConfiguration(SystemReader delegate, File userConfiguration) {
            super(delegate);
            this.userConfiguration = userConfiguration;
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return new FileBasedConfig(parent, userConfiguration, fs);
        }
    }
}
