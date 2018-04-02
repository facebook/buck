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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/** File system provider that replaces default HotSpot provider for Buck-specific optimizations */
public class BuckFileSystemProvider extends FileSystemProvider {

  private FileSystemProvider defaultProvider;

  public BuckFileSystemProvider(FileSystemProvider defaultProvider) {
    this.defaultProvider = defaultProvider;
  }

  /**
   * Returns the URI scheme that identifies this provider.
   *
   * @return The URI scheme
   */
  @Override
  public String getScheme() {
    return defaultProvider.getScheme();
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    return defaultProvider.newFileSystem(uri, env);
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    return defaultProvider.getFileSystem(uri);
  }

  @Override
  public Path getPath(URI uri) {
    // TODO: return proper path
    return defaultProvider.getPath(uri);
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return defaultProvider.newByteChannel(path, options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    return newDirectoryStream(dir, filter);
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    defaultProvider.createDirectory(dir, attrs);
  }

  @Override
  public void delete(Path path) throws IOException {
    defaultProvider.delete(path);
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    defaultProvider.copy(source, target, options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    defaultProvider.move(source, target, options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    return defaultProvider.isSameFile(path, path2);
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return defaultProvider.isHidden(path);
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return defaultProvider.getFileStore(path);
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    defaultProvider.checkAccess(path, modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    return defaultProvider.getFileAttributeView(path, type, options);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    return defaultProvider.readAttributes(path, type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return defaultProvider.readAttributes(path, attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    defaultProvider.setAttribute(path, attribute, value, options);
  }
}
