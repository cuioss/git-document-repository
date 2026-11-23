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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The main class of the check of the generated output (GDR-FORMS-4), runnable from CI without the application:
 *
 * <pre>
 * java ... de.cuioss.git.document.forms.VerifyOutput &lt;git-dir&gt; &lt;revision&gt; per-document|numbered &lt;collection&gt; &lt;schema-file&gt; &lt;template-file&gt;
 * </pre>
 *
 * It describes the kind to check by its arguments, so it needs neither the configuration of the kinds nor the
 * repository registry: the layout ({@code per-document} or {@code numbered}), the collection, and the schema and the
 * template files, read from the file system. It opens the repository at {@code <git-dir>} on a {@link RefusingFS},
 * runs {@link OutputCheck} on the commit of {@code <revision>}, and prints one line per mismatching output path to
 * standard output. It exits with
 * <ul>
 * <li>{@code 0} when every checked output matches its data,</li>
 * <li>{@code 1} when an output mismatches or is missing, and</li>
 * <li>{@code 2} for a usage error, an argument that is no valid path, a schema or template file that is missing, a
 * symbolic link, larger than {@value DocumentTemplate#MAX_TEMPLATE_BYTES} bytes or refused, a collection that is no
 * name segment, a repository that cannot be opened, or any other non-success, printed to standard error as
 * {@code <outcome> <detail>}.</li>
 * </ul>
 * It needs no network. Nothing printed names data, a pseudonym or a secret: a mismatch names the output path, a
 * refusal names the argument or a path of the commit and the problem. Standard output and standard error are written
 * in UTF-8 and flushed per line.
 *
 * @since 0.1
 */
public final class VerifyOutput {

    /** The exit code of a check whose outputs all match. */
    public static final int MATCHING = 0;

    /** The exit code of a check that found a mismatching output. */
    public static final int MISMATCHING = 1;

    /** The exit code of a usage error or a non-success. */
    public static final int FAILED = 2;

    /** The layout argument of a per-document kind. */
    public static final String PER_DOCUMENT = "per-document";

    /** The layout argument of a numbered kind. */
    public static final String NUMBERED = "numbered";

    /** The usage line printed for a usage error. */
    static final String USAGE = "usage: VerifyOutput <git-dir> <revision> per-document|numbered <collection>"
            + " <schema-file> <template-file>";

    /** The diagnostic of an argument that is no valid path; it names neither the argument nor the path. */
    static final String INVALID_PATH = "MALFORMED arguments: not a valid path";

    private static final int ARGUMENTS = 6;

    private VerifyOutput() {
    }

    /**
     * Runs the check and exits the virtual machine with its exit code.
     *
     * @param args {@code <git-dir> <revision> per-document|numbered <collection> <schema-file> <template-file>}
     */
    public static void main(String[] args) {
        int code;
        try (var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
             var err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, UTF_8)) {
            code = run(args, out, err);
        }
        System.exit(code);
    }

    /**
     * Runs the check.
     *
     * @param args {@code <git-dir> <revision> per-document|numbered <collection> <schema-file> <template-file>}
     * @param out  where the mismatching output paths are printed
     * @param err  where a usage error or a non-success is printed
     * @return {@value #MATCHING}, {@value #MISMATCHING} or {@value #FAILED}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (args.length != ARGUMENTS || !(PER_DOCUMENT.equals(args[2]) || NUMBERED.equals(args[2]))) {
            err.println(USAGE);
            return FAILED;
        }
        Path gitDir;
        Path schemaFile;
        Path templateFile;
        try {
            gitDir = Path.of(args[0]);
            schemaFile = Path.of(args[4]);
            templateFile = Path.of(args[5]);
        } catch (InvalidPathException e) {
            err.println(INVALID_PATH);
            return FAILED;
        }
        FormsResult<byte[]> schemaContent = read(schemaFile, "schema-file");
        if (!schemaContent.isOk()) {
            return failed(err, schemaContent);
        }
        FormsResult<byte[]> templateContent = read(templateFile, "template-file");
        if (!templateContent.isOk()) {
            return failed(err, templateContent);
        }
        FormsResult<FormSchema> schema = FormSchema.parse("schema-file",
                Objects.requireNonNull(schemaContent.value()));
        if (!schema.isOk()) {
            return failed(err, schema);
        }
        FormsResult<DocumentTemplate> template = DocumentTemplate.parse("template-file",
                Objects.requireNonNull(templateContent.value()));
        if (!template.isOk()) {
            return failed(err, template);
        }
        OutputCheck check;
        try {
            check = new OutputCheck(kind(args[3], NUMBERED.equals(args[2])), Objects.requireNonNull(schema.value()),
                    template.value());
        } catch (IllegalArgumentException e) {
            err.println(StoreOutcome.MALFORMED + " collection: not a name segment");
            return FAILED;
        }
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile())
                     .setFS(new RefusingFS()).setMustExist(true).build()) {
            FormsResult<OutputCheck.Comparison> compared = check.compare(repository, args[1]);
            if (!compared.isOk()) {
                return failed(err, compared);
            }
            var mismatching = Objects.requireNonNull(compared.value()).mismatching();
            mismatching.forEach(out::println);
            return mismatching.isEmpty() ? MATCHING : MISMATCHING;
        } catch (IOException | IllegalArgumentException e) {
            err.println(GitOutcome.NOT_A_REPOSITORY + " git-dir: not a repository");
            return FAILED;
        }
    }

    /**
     * The kind the arguments describe. Only the collection, the numbering and the presence of a template shape the
     * layout and the check; the other components are placeholders the check never reads.
     */
    private static Kind kind(String collection, boolean numbered) {
        @Nullable Numbering numbering = numbered ? new Numbering("", 1) : null;
        return new Kind(collection, "-", collection, "schema-file", "template-file", "-", numbering);
    }

    /** Reads a file of at most {@value DocumentTemplate#MAX_TEMPLATE_BYTES} bytes, never through a symbolic link. */
    private static FormsResult<byte[]> read(Path file, String argument) {
        if (Files.isSymbolicLink(file)) {
            return malformed(argument, "a symbolic link, not read");
        }
        byte[] content;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                     LinkOption.NOFOLLOW_LINKS); InputStream in = Channels.newInputStream(channel)) {
            content = in.readNBytes(DocumentTemplate.MAX_TEMPLATE_BYTES + 1);
        } catch (NoSuchFileException e) {
            return malformed(argument, "missing file");
        } catch (IOException e) {
            return malformed(argument, "not readable");
        }
        if (content.length > DocumentTemplate.MAX_TEMPLATE_BYTES) {
            return malformed(argument, "too large, more than " + DocumentTemplate.MAX_TEMPLATE_BYTES + " bytes");
        }
        return FormsResult.ok(content);
    }

    private static <T> FormsResult<T> malformed(String argument, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, argument + ": " + problem));
    }

    private static int failed(PrintStream err, FormsResult<?> result) {
        String outcome = switch (result.outcome()) {
            case FormsOutcome forms -> forms.name();
            case FormsResult.PassedOn(StoreOutcome store) -> store.name();
            case FormsResult.PassedOn(StoreResult.PassedOn(GitOutcome git)) -> git.name();
        };
        err.println(outcome + " " + result.detail());
        return FAILED;
    }
}
