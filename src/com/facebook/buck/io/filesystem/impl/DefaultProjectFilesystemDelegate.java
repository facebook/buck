/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Default implementation of {@link ProjectFilesystemDelegate} that talks to the filesystem via
 * standard Java APIs.
 */
public final class DefaultProjectFilesystemDelegate implements ProjectFilesystemDelegate {

  private final Path root;

  public DefaultProjectFilesystemDelegate(Path root) {
    this.root = root;
  }

  @Override
  public ImmutableMap<String, ? extends Object> getDetailsForLogging() {
    return ImmutableMap.of("filesystem", "default", "filesystem.root", root.toString());
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);

    // Normally, we would just use `Files.hash(fileToHash.toFile(), Hashing.sha1())`, but if
    // fileToHash is backed by Jimfs, its toFile() method throws an UnsupportedOperationException.
    // Creating the input stream via java.nio.file.Files.newInputStream() avoids this issue.
    ByteSource source =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            // No need to wrap with BufferedInputStream because ByteSource uses ByteStreams.copy(),
            // which already buffers.
            return Files.newInputStream(fileToHash);
          }
        };
    HashCode hashCode = source.hash(Hashing.sha1());
    return Sha1HashCode.fromHashCode(hashCode);
  }

  @Override
  public Path getPathForRelativePath(Path pathRelativeToProjectRoot) {
    // We often create {@link Path} instances using
    // {@link java.nio.file.Paths#get(String, String...)}, but there's no guarantee that the
    // underlying {@link FileSystem} is the default one.
    if (pathRelativeToProjectRoot.getFileSystem().equals(root.getFileSystem())) {
      return root.resolve(pathRelativeToProjectRoot);
    }
    return root.resolve(pathRelativeToProjectRoot.toString());
  }

  @Override
  public boolean isExecutable(Path child) {
    child = getPathForRelativePath(child);
    return Files.isExecutable(child);
  }

  @Override
  public boolean isSymlink(Path path) {
    return Files.isSymbolicLink(getPathForRelativePath(path));
  }

  @Override
  public boolean exists(Path pathRelativeToProjectRoot, LinkOption... options) {
    return Files.exists(getPathForRelativePath(pathRelativeToProjectRoot), options);
  }
}
