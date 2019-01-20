/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli.bootstrapper.filesystem;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * File system provider that replaces default Java provider for Buck-specific optimizations, mostly
 * memory footprint on Path implementation. The provider works like a wrapper, delegating most calls
 * to default FileSystemProvider by converting {@code BuckUnixPath} to java-default Path
 */
public class BuckFileSystemProvider extends FileSystemProvider {

  private FileSystemProvider defaultProvider;
  private BuckFileSystem fileSystem;
  private FileSystem defaultFileSystem;

  public BuckFileSystemProvider(FileSystemProvider defaultProvider) {
    this.defaultProvider = defaultProvider;
    this.defaultFileSystem = defaultProvider.getFileSystem(getRootURI(defaultProvider.getScheme()));
    String userDir = System.getProperty("user.dir");
    fileSystem = new BuckFileSystem(this, userDir == null ? "" : userDir);
  }

  /**
   * @return Default filesystem, i.e. filesystem that would be created if program started without
   *     filesystem override. This is used to delegate calls that do not need to be intercepted or
   *     replaced.
   */
  FileSystem getDefaultFileSystem() {
    return defaultFileSystem;
  }

  @Override
  public String getScheme() {
    return defaultProvider.getScheme();
  }

  private static URI getRootURI(String scheme) {
    try {
      return new URI(scheme, null, "/", null, null);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private void checkUri(URI uri) {
    if (!uri.getScheme().equalsIgnoreCase(this.getScheme())) {
      throw new IllegalArgumentException("URI does not match this provider");
    } else if (uri.getAuthority() != null) {
      throw new IllegalArgumentException("Authority component present");
    } else if (uri.getPath() == null) {
      throw new IllegalArgumentException("Path component is undefined");
    } else if (!uri.getPath().equals("/")) {
      throw new IllegalArgumentException("Path component should be '/'");
    } else if (uri.getQuery() != null) {
      throw new IllegalArgumentException("Query component present");
    } else if (uri.getFragment() != null) {
      throw new IllegalArgumentException("Fragment component present");
    }
  }

  /**
   * Convert {@code path} to default java path implementation. This is required for default file
   * system / provider to work properly because it assumes Path to be an object of specific type
   * (more precisely, it has to be UnixPath for Sun Java implementation)
   */
  private Path asDefault(Path path) {
    if (path instanceof BuckUnixPath) {
      return ((BuckUnixPath) path).asDefault();
    }
    return path;
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
    checkUri(uri);
    throw new FileSystemAlreadyExistsException();
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    checkUri(uri);
    return fileSystem;
  }

  @Override
  public Path getPath(URI uri) {
    return BuckUnixPath.of(fileSystem, defaultProvider.getPath(uri).toString());
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return defaultProvider.newByteChannel(asDefault(path), options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    DirectoryStream<Path> defaultStream =
        defaultProvider.newDirectoryStream(asDefault(dir), filter);

    // convert DirectoryStream<Path> to DirectoryStream<BuckUnixPath>
    return new DirectoryStream<Path>() {
      @Override
      public Iterator<Path> iterator() {
        return new Iterator<Path>() {
          private Iterator<Path> defaultIterator = defaultStream.iterator();

          @Override
          public boolean hasNext() {
            return defaultIterator.hasNext();
          }

          @Override
          public Path next() {
            Path next = defaultIterator.next();
            if (next instanceof BuckUnixPath) {
              return next;
            }
            return BuckUnixPath.of(fileSystem, next.toString());
          }
        };
      }

      @Override
      public void close() throws IOException {
        defaultStream.close();
      }
    };
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    defaultProvider.createDirectory(asDefault(dir), attrs);
  }

  @Override
  public void delete(Path path) throws IOException {
    defaultProvider.delete(asDefault(path));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    defaultProvider.copy(asDefault(source), asDefault(target), options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    defaultProvider.move(asDefault(source), asDefault(target), options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    return defaultProvider.isSameFile(asDefault(path), asDefault(path2));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return defaultProvider.isHidden(asDefault(path));
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return defaultProvider.getFileStore(asDefault(path));
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    defaultProvider.checkAccess(asDefault(path), modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    return defaultProvider.getFileAttributeView(asDefault(path), type, options);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    return defaultProvider.readAttributes(asDefault(path), type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return defaultProvider.readAttributes(asDefault(path), attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    defaultProvider.setAttribute(asDefault(path), attribute, value, options);
  }

  @Override
  public FileChannel newFileChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return defaultProvider.newFileChannel(asDefault(path), options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(
      Path path,
      Set<? extends OpenOption> options,
      ExecutorService executor,
      FileAttribute<?>... attrs)
      throws IOException {
    return defaultProvider.newAsynchronousFileChannel(asDefault(path), options, executor, attrs);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    defaultProvider.createSymbolicLink(asDefault(link), asDefault(target), attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    defaultProvider.createLink(asDefault(link), asDefault(existing));
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    Path defaultPath = defaultProvider.readSymbolicLink(asDefault(link));
    return BuckUnixPath.of(fileSystem, defaultPath.toString());
  }
}
