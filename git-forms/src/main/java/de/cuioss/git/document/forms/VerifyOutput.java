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
import java.util.Locale;
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
 * template files, read from the file system, never through a symbolic link in any component of the path as given, the
 * parent directories included. It opens the repository at {@code <git-dir>} on a {@link RefusingFS},
 * runs {@link OutputCheck} on the commit of {@code <revision>}, and prints one line per mismatching output path to
 * standard output. It exits with
 * <ul>
 * <li>{@code 0} when every checked output matches its data,</li>
 * <li>{@code 1} when an output mismatches or is missing, and</li>
 * <li>{@code 2} for a usage error, an argument that is no valid path, a schema or template file that is missing, a
 * symbolic link or reached through one, larger than {@value DocumentTemplate#MAX_TEMPLATE_BYTES} bytes or refused, a collection that is no
 * name segment, a repository that cannot be opened, or any other non-success, printed to standard error as
 * {@code <outcome> <detail>}.</li>
 * </ul>
 * It needs no network. Nothing printed names data, a pseudonym or a secret: a mismatch names the output path, a
 * refusal names the argument or a path of the commit and the problem; data nested deeper than the nesting limit of the
 * data model is such a refusal, {@code MALFORMED} naming the limit. Every printed line is one line: a backslash of a path
 * or a detail is printed as {@code \\}, a control character, a line or a paragraph separator as {@code \}{@code uXXXX},
 * so a path that holds a line feed cannot split into two lines a CI consumer would read as two paths. Standard output
 * and standard error are written in UTF-8 and flushed per line.
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

    /** The name of the schema file argument, named in a refusal. */
    private static final String SCHEMA_FILE = "schema-file";

    /** The name of the template file argument, named in a refusal. */
    private static final String TEMPLATE_FILE = "template-file";

    /** The placeholder of a component of the kind the check never reads. */
    private static final String UNREAD = "-";

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
        FormsResult<byte[]> schemaContent = read(schemaFile, SCHEMA_FILE);
        if (!schemaContent.isOk()) {
            return failed(err, schemaContent);
        }
        FormsResult<byte[]> templateContent = read(templateFile, TEMPLATE_FILE);
        if (!templateContent.isOk()) {
            return failed(err, templateContent);
        }
        FormsResult<FormSchema> schema = FormSchema.parse(SCHEMA_FILE,
                Objects.requireNonNull(schemaContent.value()));
        if (!schema.isOk()) {
            return failed(err, schema);
        }
        FormsResult<DocumentTemplate> template = DocumentTemplate.parse(TEMPLATE_FILE,
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
        Repository opened;
        try {
            opened = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setFS(new RefusingFS())
                    .setMustExist(true).build();
        } catch (IOException | IllegalArgumentException e) {
            err.println(GitOutcome.NOT_A_REPOSITORY + " git-dir: not a repository");
            return FAILED;
        }
        try (Repository repository = opened) {
            FormsResult<OutputCheck.Comparison> compared = check.compare(repository, args[1]);
            if (!compared.isOk()) {
                return failed(err, compared);
            }
            var mismatching = Objects.requireNonNull(compared.value()).mismatching();
            mismatching.forEach(path -> out.println(printable(path)));
            return mismatching.isEmpty() ? MATCHING : MISMATCHING;
        }
    }

    /**
     * The form of a text printed on one line: a backslash becomes {@code \\}, and a control character, a line or a
     * paragraph separator becomes {@code \}{@code uXXXX} with its four hexadecimal digits, so a path that holds a line
     * feed is printed on one line and the printed form names exactly one path.
     *
     * @param text the text, a path of the commit or a detail naming one
     * @return the text with every such character escaped
     */
    static String printable(String text) {
        StringBuilder printed = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            int type = Character.getType(c);
            if (c == '\\') {
                printed.append("\\\\");
            } else if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                printed.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                printed.append(c);
            }
        }
        return printed.toString();
    }

    /**
     * The kind the arguments describe. Only the collection, the numbering and the presence of a template shape the
     * layout and the check; the other components are placeholders the check never reads.
     */
    private static Kind kind(String collection, boolean numbered) {
        @Nullable Numbering numbering = numbered ? new Numbering("", 1) : null;
        return new Kind(collection, UNREAD, collection, SCHEMA_FILE, TEMPLATE_FILE, UNREAD, numbering);
    }

    /**
     * Reads a file of at most {@value DocumentTemplate#MAX_TEMPLATE_BYTES} bytes, never through a symbolic link: a path
     * any component of which, the file or a parent directory, is a symbolic link is refused unread.
     */
    private static FormsResult<byte[]> read(Path file, String argument) {
        if (holdsSymbolicLink(file)) {
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

    /**
     * Whether a component of the path as given is a symbolic link: each prefix below the root, from the first element
     * down to the file itself, is tested without following a link. The path is neither normalised nor resolved, which
     * would hide a link; a prefix that does not exist is no link, so a missing file stays a missing file.
     */
    private static boolean holdsSymbolicLink(Path file) {
        @Nullable Path prefix = file.getRoot();
        for (Path element : file) {
            prefix = prefix == null ? element : prefix.resolve(element);
            if (Files.isSymbolicLink(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static <T> FormsResult<T> malformed(String argument, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, argument + ": " + problem));
    }

    private static int failed(PrintStream err, FormsResult<?> result) {
        err.println(printable(TransitionOutcomes.name(result.outcome()) + " " + result.detail()));
        return FAILED;
    }
}
