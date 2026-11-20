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
package de.cuioss.git.document.core.worktrees;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import de.cuioss.git.document.core.worktrees.WorktreeOperations.Worktree;
import lombok.experimental.UtilityClass;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The linked-worktree administration files of git, read and written natively, since JGit has no
 * {@code git worktree} command. A linked worktree {@code <name>} consists of
 * {@code <common>/worktrees/<name>/} holding {@code gitdir} (the path of the worktree's {@code .git} file),
 * {@code commondir} ({@code ../..}), {@code HEAD}, its index, and an optional {@code locked} file, and of the
 * worktree's {@code .git} file pointing back with {@code gitdir: <admin dir>}. The layout is git's own, so
 * {@code git worktree list} reads it.
 * <p>
 * The code is taken from {@code pm-provider-git} and changed in five points. The administration directory is claimed
 * atomically: {@link #create(Path, Path, String)} creates it with {@link Files#createDirectory} and moves to the next
 * numeric suffix when the name is taken, so two concurrent creations on one repository never share an administration
 * directory. Every file is written to a partial file and moved into place, {@code HEAD} last, and a creation that
 * fails removes the administration directory it claimed. {@link #list(Path)} skips an entry whose {@code gitdir} or
 * {@code HEAD} is not (yet, or no longer) present, and resolves a relative {@code gitdir} against the administration
 * directory. {@link #reserveBranch(Path, String)} reserves a branch, a new one like an existing one, for one creation,
 * and {@link #releaseBranch(Path, String)} deletes a reservation an ended process left behind.
 *
 * @since 0.1
 */
@UtilityClass
final class LinkedWorktrees {

    static final String WORKTREES = "worktrees";
    static final String LOCKED = "locked";
    private static final String COMMONDIR = "commondir";
    private static final String REF_PREFIX = "ref: ";

    /** The suffix of a file while it is written, before it is moved into place. */
    private static final String PARTIAL_SUFFIX = ".partial";

    /** The prefix of the reservation file of a branch in the common directory. */
    private static final String RESERVATION_PREFIX = "worktree-branch-";

    /**
     * Writes the administration files of a new linked worktree, locked while initializing. {@code HEAD} is written
     * last, so {@link #list(Path)} lists the worktree only once its {@code gitdir} and {@code HEAD} are complete. A
     * failed write removes the claimed administration directory and leaves the worktree directory to the caller.
     *
     * @param commonDir the common git directory
     * @param worktree  the canonical worktree root (must exist)
     * @param head      the content of {@code HEAD}: a full ref name or a commit id
     * @return the administration directory
     * @throws IOException if a file cannot be written
     */
    static Path create(Path commonDir, Path worktree, String head) throws IOException {
        Path admin = claimAdminDir(commonDir.resolve(WORKTREES), worktree.getFileName().toString());
        try {
            write(admin.resolve(LOCKED), "initializing");
            write(admin.resolve(Constants.GITDIR_FILE), worktree.resolve(Constants.DOT_GIT).toString());
            write(admin.resolve(COMMONDIR), "../..");
            write(admin.resolve(Constants.HEAD), head.startsWith(Constants.R_REFS) ? REF_PREFIX + head : head);
            write(worktree.resolve(Constants.DOT_GIT), "gitdir: " + admin);
        } catch (IOException e) {
            try {
                deleteTree(admin);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return admin;
    }

    /**
     * Lists the linked worktrees of a common git directory, ordered by name. An administration directory without
     * {@code gitdir} or {@code HEAD} is being initialized or removed and is skipped; a locked worktree whose files are
     * complete is listed, locked.
     *
     * @param commonDir the common git directory
     * @return the linked worktrees
     * @throws IOException if a present administration file cannot be read
     */
    static List<Worktree> list(Path commonDir) throws IOException {
        Path base = commonDir.resolve(WORKTREES);
        var result = new ArrayList<Worktree>();
        if (!Files.isDirectory(base)) {
            return result;
        }
        var admins = new ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base, Files::isDirectory)) {
            entries.forEach(admins::add);
        }
        admins.sort(null);
        for (Path admin : admins) {
            entry(admin).ifPresent(result::add);
        }
        return result;
    }

    private static Optional<Worktree> entry(Path admin) throws IOException {
        Path gitFile;
        String head;
        try {
            gitFile = admin.resolve(read(admin.resolve(Constants.GITDIR_FILE))).normalize();
            head = read(admin.resolve(Constants.HEAD));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        Optional<String> branch = head.startsWith(REF_PREFIX)
                ? Optional.of(shortBranch(head.substring(REF_PREFIX.length())))
                : Optional.empty();
        Optional<String> id = ObjectId.isId(head) ? Optional.of(head) : Optional.empty();
        return Optional.of(Worktree.builder()
                .path(gitFile.getParent())
                .name(admin.getFileName().toString())
                .branch(branch.orElse(null))
                .head(id.orElse(null))
                .main(false)
                .locked(Files.exists(admin.resolve(LOCKED)))
                .prunable(!Files.exists(gitFile))
                .build());
    }

    /** Deletes a worktree directory (never following symbolic links) and its administration directory. */
    static void delete(Path commonDir, String name, Path worktree) throws IOException {
        deleteTree(worktree);
        deleteAdmin(commonDir, name);
    }

    /** Deletes the administration directory of a worktree and nothing else. */
    static void deleteAdmin(Path commonDir, String name) throws IOException {
        deleteTree(commonDir.resolve(WORKTREES).resolve(name));
    }

    /** Removes the initialization lock of the administration directory {@code admin}. */
    static void unlock(Path admin) throws IOException {
        Files.deleteIfExists(admin.resolve(LOCKED));
    }

    /**
     * Reserves a branch for one worktree creation: the reservation file of the branch in the common directory,
     * {@code worktree-branch-<SHA-1 of the full branch name>.lock}, is created with {@link Files#createFile}, which
     * fails when it exists, so of two concurrent reservations exactly one succeeds. The holder releases it with
     * {@link #release(Path)} once the worktree is checked out and unlocked, or once the rollback of a failed creation
     * has deleted the branch it created.
     * A reservation left by an ended process stays until {@link #releaseBranch(Path, String)} deletes it, which
     * {@link WorktreeOperations#releaseBranchReservation(Path, String)} offers to the caller.
     *
     * @param commonDir the common git directory
     * @param branchRef the full name of the branch
     * @return the reservation, or empty if the branch is reserved by another creation
     * @throws IOException if the reservation file cannot be created
     */
    static Optional<Path> reserveBranch(Path commonDir, String branchRef) throws IOException {
        try {
            return Optional.of(Files.createFile(reservationFile(commonDir, branchRef)));
        } catch (FileAlreadyExistsException e) {
            return Optional.empty();
        }
    }

    /** Releases a branch reservation returned by {@link #reserveBranch(Path, String)}. */
    static void release(Path reservation) throws IOException {
        Files.deleteIfExists(reservation);
    }

    /**
     * Deletes the reservation file of a branch, whoever created it, with one atomic {@link Files#delete}, so of two
     * concurrent deletions exactly one reports it. Nothing else is deleted.
     *
     * @param commonDir the common git directory
     * @param branchRef the full name of the branch, a valid reference name
     * @return whether a reservation was deleted; {@code false} if the branch had none
     * @throws IOException if the reservation file cannot be deleted
     */
    static boolean releaseBranch(Path commonDir, String branchRef) throws IOException {
        try {
            Files.delete(reservationFile(commonDir, branchRef));
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /** The reservation file of a branch: named by the SHA-1 of its full name, so no caller string forms the name. */
    private static Path reservationFile(Path commonDir, String branchRef) {
        var digest = Constants.newMessageDigest().digest(branchRef.getBytes(StandardCharsets.UTF_8));
        return commonDir.resolve(RESERVATION_PREFIX + ObjectId.fromRaw(digest).name() + ".lock");
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String shortBranch(String ref) {
        return ref.startsWith(Constants.R_HEADS) ? ref.substring(Constants.R_HEADS.length()) : ref;
    }

    /**
     * Claims a new administration directory: {@code <base>/<name>}, else the first free {@code <base>/<name><n>}.
     * {@link Files#createDirectory} fails on an existing directory, so the claim and the test for a free name are one
     * atomic step and no other creation can take the same directory.
     */
    private static Path claimAdminDir(Path base, String name) throws IOException {
        Files.createDirectories(base);
        Path candidate = base.resolve(name);
        int suffix = 1;
        while (true) {
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException e) {
                candidate = base.resolve(name + suffix++);
            }
        }
    }

    /** Writes a file completely before it becomes visible under its name; a failed write leaves no partial file. */
    private static void write(Path file, String content) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.writeString(partial, content + "\n");
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file).trim();
    }
}
