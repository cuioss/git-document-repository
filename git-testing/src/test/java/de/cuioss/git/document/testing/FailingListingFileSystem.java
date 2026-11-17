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

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A file system holding a single directory whose listing fails with a given exception, the failure a real file
 * system reports when reading a directory's entries goes wrong after the directory was opened. A walk of the
 * directory therefore ends it with that failure. Deleting the directory only records the deletion.
 * <p>
 * Every operation a walk and a deletion of the directory do not need is unsupported.
 */
final class FailingListingFileSystem extends FileSystem {

    private final IOException listingFailure;

    private final Provider provider = new Provider();

    private final Path directory;

    private volatile boolean deleted;

    /**
     * Creates the file system.
     *
     * @param listingFailure the failure reading the directory's entries reports
     */
    FailingListingFileSystem(IOException listingFailure) {
        this.listingFailure = listingFailure;
        directory = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getFileSystem" -> this;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "failing-listing-directory";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * @return the single directory of this file system
     */
    Path directory() {
        return directory;
    }

    /**
     * @return whether the directory was deleted
     */
    boolean deleted() {
        return deleted;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    /**
     * The attributes of the single directory.
     */
    private static final class DirectoryAttributes implements BasicFileAttributes {

        private static final FileTime EPOCH = FileTime.fromMillis(0);

        @Override
        public FileTime lastModifiedTime() {
            return EPOCH;
        }

        @Override
        public FileTime lastAccessTime() {
            return EPOCH;
        }

        @Override
        public FileTime creationTime() {
            return EPOCH;
        }

        @Override
        public boolean isRegularFile() {
            return false;
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }

    /**
     * The provider of the file system: it lists the directory with the failure and records its deletion.
     */
    private final class Provider extends FileSystemProvider {

        @Override
        public String getScheme() {
            return "failing-listing";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                FileAttribute<?>... attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
            return new DirectoryStream<>() {

                @Override
                public Iterator<Path> iterator() {
                    return new Iterator<>() {

                        @Override
                        public boolean hasNext() {
                            throw new DirectoryIteratorException(listingFailure);
                        }

                        @Override
                        public Path next() {
                            throw new DirectoryIteratorException(listingFailure);
                        }
                    };
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Path path) {
            deleted = true;
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameFile(Path path, Path path2) {
            return path.equals(path2);
        }

        @Override
        public boolean isHidden(Path path) {
            return false;
        }

        @Override
        public FileStore getFileStore(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) {
            // the single directory is accessible
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) {
            if (type != BasicFileAttributes.class) {
                throw new UnsupportedOperationException(type.getName());
            }
            return type.cast(new DirectoryAttributes());
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }
}
