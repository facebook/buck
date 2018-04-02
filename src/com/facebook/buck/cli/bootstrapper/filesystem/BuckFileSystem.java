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
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Set;

/**
 * File system implementation that returns memory footprint optimized Path object. It delegates
 * other calls to Java-default file system.
 */
public class BuckFileSystem extends FileSystem {

  private BuckFileSystemProvider provider;
  private BuckUnixPath rootDirectory = new BuckUnixPath(this, new String[] {""}, true);
  private BuckUnixPath emptyPath = new BuckUnixPath(this, new String[0], false);
  private BuckUnixPath defaultDirectory;

  /**
   * Create a new filesystem that returns Path object optimized for memory usage
   *
   * @param provider File system provider that created this filesystem
   * @param defaultDirectory The directory to use when constructing absolute paths out of relative
   *     paths, usually this is a user directory
   */
  public BuckFileSystem(BuckFileSystemProvider provider, String defaultDirectory) {
    this.provider = provider;
    this.defaultDirectory = BuckUnixPath.of(this, defaultDirectory);
  }

  @Override
  public FileSystemProvider provider() {
    return provider;
  }

  /** @return root directory of current filesystem */
  BuckUnixPath getRootDirectory() {
    return rootDirectory;
  }

  /** @return default directory, usually user directory */
  BuckUnixPath getDefaultDirectory() {
    return defaultDirectory;
  }

  /** @return empty path, used to indicate that path has no elements */
  BuckUnixPath getEmptyPath() {
    return emptyPath;
  }

  /**
   * @return delegate file system, i.e. the one used originally by Java runtime for current platform
   */
  FileSystem getDefaultFileSystem() {
    return provider.getDefaultFileSystem();
  }

  @Override
  public void close() throws IOException {
    getDefaultFileSystem().close();
  }

  @Override
  public boolean isOpen() {
    return getDefaultFileSystem().isOpen();
  }

  @Override
  public boolean isReadOnly() {
    return getDefaultFileSystem().isReadOnly();
  }

  @Override
  public String getSeparator() {
    return getDefaultFileSystem().getSeparator();
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    ArrayList<Path> buckRootDirs = new ArrayList<>();
    getDefaultFileSystem()
        .getRootDirectories()
        .forEach(dir -> buckRootDirs.add(BuckUnixPath.of(this, dir.toString())));
    return buckRootDirs;
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    return getDefaultFileSystem().getFileStores();
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return getDefaultFileSystem().supportedFileAttributeViews();
  }

  @Override
  public Path getPath(String first, String... more) {
    String path;
    if (more.length == 0) {
      path = first;
    } else {
      StringBuilder sb = new StringBuilder();
      sb.append(first);
      for (String segment : more) {
        if (!segment.isEmpty()) {
          if (sb.length() > 0) {
            sb.append('/');
          }
          sb.append(segment);
        }
      }
      path = sb.toString();
    }
    return BuckUnixPath.of(this, path);
  }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    return getDefaultFileSystem().getPathMatcher(syntaxAndPattern);
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return getDefaultFileSystem().getUserPrincipalLookupService();
  }

  @Override
  public WatchService newWatchService() throws IOException {
    return getDefaultFileSystem().newWatchService();
  }
}
