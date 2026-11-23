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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;


import dev.harrel.jsonschema.Validator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The native-image metadata of {@code git-forms} (GDR-NFR-4) lists exactly what the code needs: {@code
 * resource-config.json} is on the class path, every resource pattern it lists names a resource that resolves and every
 * resource bundle it lists resolves; and every resource of {@code git-forms} and of {@code dev.harrel:json-schema} that
 * {@link FormSchema#parse(String, byte[])}, {@link SchemaValidator#validate(FormSchema, Map)} and
 * {@link SchemaValidator#required(FormSchema, Map)} request at run time is listed. The requests are recorded by a class
 * loader that loads those two artifacts and snakeyaml-engine child-first, so that every resource lookup of their code
 * passes through it; a json-schema upgrade that moves or adds a resource therefore fails this build instead of the
 * native image of a consumer.
 * <p>
 * {@code reflect-config.json} is absent, because no code path of {@code git-forms} uses reflection. For snakeyaml-engine
 * the claim is that version 3.2 needs no metadata for the event parser {@link YamlReader} is built on: its jar holds no
 * resource besides class files and Maven metadata (checked against the listing of {@code snakeyaml-engine-3.2.jar}),
 * {@link YamlReader} requests none of them, and no class of snakeyaml-engine the reads load references
 * {@code java.lang.reflect}, {@code Class.forName}, {@code newInstance} or a reflective member lookup of {@code Class}
 * (checked against the constant pools of the class files of {@code snakeyaml-engine} 3.2, by this test). The names of
 * the metadata file are taken by plain string extraction, without a JSON library.
 */
@DisplayName("Native-image metadata of git-forms (GDR-NFR-4)")
class NativeImageMetadataTest {

    /** The directory of the metadata files on the class path. */
    private static final String METADATA_DIRECTORY = "META-INF/native-image/de.cuioss/git-forms/";

    /** The file listing the resources and resource bundles. */
    private static final String RESOURCE_CONFIG = METADATA_DIRECTORY + "resource-config.json";

    /** The file listing reflectively reached types, which git-forms does not need. */
    private static final String REFLECT_CONFIG = METADATA_DIRECTORY + "reflect-config.json";

    /** A {@code "pattern"} entry of the form {@code \\Q<resource>\\E}, a literal resource path. */
    private static final Pattern LITERAL_PATTERN = Pattern.compile("\"pattern\"\\s*:\\s*\"\\\\\\\\Q(.+?)\\\\\\\\E\"");

    /** A {@code "name"} entry whose value is a qualified name, as a bundle is listed. */
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([\\w$]+(?:\\.[\\w$]+)+)\"");

    /** The resource of the message bundle of the validator, which its default message provider loads. */
    private static final String MESSAGE_BUNDLE = "dev/harrel/jsonschema/messages.properties";

    /** The meta-schema of Draft 2020-12, against which a schema is checked. */
    private static final String META_SCHEMA = "draft2020-12.json";

    /** The constant-pool names by which a class file uses reflection. */
    private static final List<String> REFLECTION = List.of("java/lang/reflect/", "forName", "newInstance",
            "getDeclaredMethod", "getDeclaredField", "getDeclaredConstructor", "getMethod", "getField",
            "getConstructor");

    private static final String FORMS = "de.cuioss.git.document.forms.";

    /** A schema whose evaluation reaches every applicator {@code required} walks, unevaluatedProperties included. */
    private static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "required": ["title"],
              "properties": { "title": { "type": "string", "maxLength": 10 } },
              "allOf": [ { "properties": { "extra": { "type": "object", "required": ["note"] } } } ],
              "unevaluatedProperties": { "type": "object", "required": ["name"] }
            }
            """;

    /** YAML beyond JSON: a block mapping, a block sequence, a comment and the scalars of the core schema. */
    private static final String YAML = """
            # a comment
            title: plain
            count: 0x1F
            ratio: 1.5e3
            flag: true
            empty: ~
            items:
              - 'quoted'
              - |
                block
            """;

    private static final ClassLoader LOADER = NativeImageMetadataTest.class.getClassLoader();

    @Test
    @DisplayName("GDR-NFR-4: resource-config.json is shipped, reflect-config.json is not")
    void shipsResourceConfigOnly() {
        assertAll("Metadata files under " + METADATA_DIRECTORY,
                () -> assertNotNull(LOADER.getResource(RESOURCE_CONFIG), RESOURCE_CONFIG + " must be on the class path"),
                () -> assertNull(LOADER.getResource(REFLECT_CONFIG), REFLECT_CONFIG + " must not be shipped"));
    }

    @Test
    @DisplayName("GDR-NFR-4: every resource listed in resource-config.json resolves")
    void resolvesEveryListedResource() throws Exception {
        var resources = matches(LITERAL_PATTERN);

        assertFalse(resources.isEmpty(), RESOURCE_CONFIG + " must list at least one resource");
        assertAll("Resources of " + RESOURCE_CONFIG, resources.stream()
                .map(name -> (Executable) () -> assertNotNull(LOADER.getResource(name),
                        "Listed resource must resolve: " + name)));
    }

    @Test
    @DisplayName("GDR-NFR-4: every resource bundle listed in resource-config.json resolves")
    void resolvesEveryListedBundle() throws Exception {
        var bundles = matches(QUALIFIED_NAME);

        assertFalse(bundles.isEmpty(), RESOURCE_CONFIG + " must list at least one resource bundle");
        assertAll("Bundles of " + RESOURCE_CONFIG, bundles.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(
                        () -> ResourceBundle.getBundle(name, Locale.ROOT, LOADER),
                        "Listed resource bundle must resolve: " + name)));
    }

    @Test
    @DisplayName("GDR-NFR-4: every resource parse, validate and required load from git-forms and json-schema is listed")
    void listsEveryLoadedResource() throws Exception {
        var recording = recordLoads();
        var literals = matches(LITERAL_PATTERN);
        var bundles = matches(QUALIFIED_NAME);
        Set<String> loaded = new LinkedHashSet<>(recording.resourcesOf(Origin.FORMS));
        loaded.addAll(recording.resourcesOf(Origin.VALIDATOR));

        assertAll("Resources loaded from git-forms and json-schema",
                () -> assertTrue(loaded.containsAll(List.of(MESSAGE_BUNDLE, META_SCHEMA)),
                        "the message bundle and the meta-schema are among the loaded resources: " + loaded),
                () -> assertAll(loaded.stream().map(name -> (Executable) () -> assertTrue(
                        literals.contains(name) || isOfBundle(name, bundles),
                        "Loaded resource must be listed in " + RESOURCE_CONFIG + ": " + name))));
    }

    @Test
    @DisplayName("GDR-NFR-4: YamlReader requests no resource of snakeyaml-engine and reaches it without reflection")
    void yamlReaderNeedsNoMetadata() throws Exception {
        var recording = recordLoads();
        var yamlClasses = recording.classesOf(Origin.YAML);

        assertAll("snakeyaml-engine under YamlReader",
                () -> assertEquals(List.of(), recording.resourcesOf(Origin.YAML), "no resource is requested"),
                () -> assertFalse(yamlClasses.isEmpty(), "the reads load classes of snakeyaml-engine"),
                () -> assertAll(yamlClasses.entrySet().stream().map(entry -> (Executable) () -> assertEquals(
                        List.of(), reflectionNames(entry.getValue()),
                        "Class must not use reflection: " + entry.getKey()))));
    }

    /** Tells whether a resource is a properties file of a listed bundle, of the base name or of a locale. */
    private static boolean isOfBundle(String resource, List<String> bundles) {
        return bundles.stream().map(bundle -> bundle.replace('.', '/'))
                .anyMatch(base -> resource.equals(base + ".properties")
                        || resource.startsWith(base + "_") && resource.endsWith(".properties"));
    }

    /** The reflective names in the constant pool of a class, read as Latin-1 so that every byte maps to a char. */
    private static List<String> reflectionNames(URL file) throws IOException {
        String content;
        var connection = file.openConnection();
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        return REFLECTION.stream().filter(content::contains).toList();
    }

    /**
     * Runs {@link FormSchema#parse(String, byte[])}, {@link SchemaValidator#validate(FormSchema, Map)} on valid and on
     * invalid data, {@link SchemaValidator#required(FormSchema, Map)} on data with properties left unevaluated, and
     * {@link YamlReader} on YAML beyond JSON, all under a fresh recording class loader that is also the context class
     * loader, and returns what was requested.
     */
    private static Recording recordLoads() throws Exception {
        var loader = new RecordingLoader();
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try (loader) {
            Class<?> formSchema = loader.loadClass(FORMS + "FormSchema");
            Object parsed = formSchema.getMethod("parse", String.class, byte[].class)
                    .invoke(null, "item.schema.json", SCHEMA.getBytes(StandardCharsets.UTF_8));
            Object schema = parsed.getClass().getMethod("value").invoke(parsed);
            assertNotNull(schema, () -> "the schema is read: " + invoke(parsed, "detail"));

            Class<?> validatorType = loader.loadClass(FORMS + "SchemaValidator");
            Object validator = validatorType.getConstructor().newInstance();
            var validate = validatorType.getMethod("validate", formSchema, Map.class);
            var required = validatorType.getMethod("required", formSchema, Map.class);
            Map<String, @Nullable Object> unevaluated = new LinkedHashMap<>();
            unevaluated.put("title", "short");
            unevaluated.put("extra", Map.of());
            unevaluated.put("other", Map.of());
            assertAll("the schema is evaluated",
                    () -> assertTrue(String.valueOf(validate.invoke(validator, schema, Map.of("title", "short")))
                            .startsWith("Valid"), "valid data"),
                    () -> assertTrue(String.valueOf(validate.invoke(validator, schema, Map.of("title", 5L)))
                            .startsWith("Invalid"), "invalid data"),
                    () -> assertTrue(String.valueOf(required.invoke(validator, schema, unevaluated))
                            .startsWith("MandatoryFieldsMissing"), "mandatory fields missing"));

            Class<?> yamlReader = loader.loadClass(FORMS + "YamlReader");
            var constructor = yamlReader.getDeclaredConstructor();
            constructor.setAccessible(true);
            var read = yamlReader.getDeclaredMethod("read", String.class);
            read.setAccessible(true);
            Object yaml = read.invoke(constructor.newInstance(), YAML);
            assertNotNull(yaml.getClass().getMethod("value").invoke(yaml),
                    () -> "the YAML is read: " + invoke(yaml, "detail"));
        } finally {
            thread.setContextClassLoader(context);
        }
        return new Recording(loader);
    }

    /** Calls a public method without arguments, for the message of a failed assertion. */
    private static @Nullable Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
    }

    /** Reads {@code resource-config.json} from the class path and extracts the first group of every match. */
    private static List<String> matches(Pattern pattern) throws IOException {
        String content;
        try (InputStream in = LOADER.getResourceAsStream(RESOURCE_CONFIG)) {
            assertNotNull(in, RESOURCE_CONFIG + " must be on the class path");
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return pattern.matcher(content).results().map(match -> match.group(1)).toList();
    }

    /** The artifacts the recording class loader loads child-first. */
    private enum Origin {

        /** The main classes of git-forms. */
        FORMS(FormSchema.class),

        /** dev.harrel:json-schema. */
        VALIDATOR(Validator.class),

        /** snakeyaml-engine. */
        YAML(LoadSettings.class);

        private final URL location;

        Origin(Class<?> member) {
            this.location = member.getProtectionDomain().getCodeSource().getLocation();
        }

        static @Nullable Origin of(URL resource) {
            String external = resource.toExternalForm();
            for (Origin origin : values()) {
                String root = origin.location.toExternalForm();
                if (external.startsWith(root) || external.startsWith("jar:" + root + "!/")) {
                    return origin;
                }
            }
            return null;
        }
    }

    /**
     * What a recording class loader saw, by origin: the resources requested and found, and the class file of every class
     * it defined.
     */
    private record Recording(Map<Origin, Set<String>> resources, Map<Origin, Map<String, URL>> classes) {

        Recording(RecordingLoader loader) {
            this(Map.copyOf(loader.resources), Map.copyOf(loader.classes));
        }

        List<String> resourcesOf(Origin origin) {
            return List.copyOf(resources.getOrDefault(origin, Set.of()));
        }

        Map<String, URL> classesOf(Origin origin) {
            return classes.getOrDefault(origin, Map.of());
        }
    }

    /**
     * Loads the classes of git-forms, json-schema and snakeyaml-engine itself, every other class through its parent,
     * and records every resource of those artifacts that is requested and found, and every class it defines.
     */
    private static final class RecordingLoader extends URLClassLoader {

        private final Map<Origin, Set<String>> resources = Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<Origin, Map<String, URL>> classes = Collections.synchronizedMap(new LinkedHashMap<>());

        RecordingLoader() {
            super(urls(), LOADER);
        }

        private static URL[] urls() {
            List<URL> urls = new ArrayList<>();
            for (Origin origin : Origin.values()) {
                urls.add(origin.location);
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    URL file = findResource(name.replace('.', '/') + ".class");
                    if (file == null) {
                        return super.loadClass(name, resolve);
                    }
                    loaded = findClass(name);
                    Origin origin = Origin.of(file);
                    if (origin != null) {
                        classes.computeIfAbsent(origin, key -> Collections.synchronizedMap(new LinkedHashMap<>()))
                                .put(name, file);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public @Nullable URL getResource(String name) {
            URL own = findResource(name);
            if (own != null) {
                add(resources, own, name);
                return own;
            }
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            Enumeration<URL> own = findResources(name);
            List<URL> all = new ArrayList<>();
            while (own.hasMoreElements()) {
                URL url = own.nextElement();
                add(resources, url, name);
                all.add(url);
            }
            ClassLoader parent = getParent();
            if (parent != null) {
                all.addAll(Collections.list(parent.getResources(name)));
            }
            return Collections.enumeration(all);
        }

        private static void add(Map<Origin, Set<String>> target, URL url, String name) {
            Origin origin = Origin.of(url);
            if (origin != null) {
                target.computeIfAbsent(origin, key -> Collections.synchronizedSet(new LinkedHashSet<>())).add(name);
            }
        }
    }
}
