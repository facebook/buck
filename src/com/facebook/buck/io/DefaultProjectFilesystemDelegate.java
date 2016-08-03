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

package com.facebook.buck.io;

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.IOException;
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
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    HashCode hashCode = Files.hash(fileToHash.toFile(), Hashing.sha1());
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
}
