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
import java.util.Set;

/**
 * File system implementation that returns memory-optimized Path object. It delegates other calls to
 * default file system.
 */
public class BuckFileSystem extends FileSystem {

  private FileSystem defaultFileSystem;
  private FileSystemProvider provider;

  public BuckFileSystem(FileSystem defaultFileSystem, FileSystemProvider provider) {
    this.defaultFileSystem = defaultFileSystem;
    this.provider = provider;
  }
  /**
   * Returns the provider that created this file system.
   *
   * @return The provider that created this file system.
   */
  @Override
  public FileSystemProvider provider() {
    return provider;
  }

  @Override
  public void close() throws IOException {
    defaultFileSystem.close();
  }

  @Override
  public boolean isOpen() {
    return defaultFileSystem.isOpen();
  }

  @Override
  public boolean isReadOnly() {
    return defaultFileSystem.isReadOnly();
  }

  @Override
  public String getSeparator() {
    return defaultFileSystem.getSeparator();
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return defaultFileSystem.getRootDirectories();
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    return defaultFileSystem.getFileStores();
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return defaultFileSystem.supportedFileAttributeViews();
  }

  @Override
  public Path getPath(String first, String... more) {
    return defaultFileSystem.getPath(first, more);
  }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    return defaultFileSystem.getPathMatcher(syntaxAndPattern);
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return defaultFileSystem.getUserPrincipalLookupService();
  }

  @Override
  public WatchService newWatchService() throws IOException {
    return defaultFileSystem.newWatchService();
  }
}
