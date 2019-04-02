/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.hashing;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A {@link FileHashLoader} that only hashes the files' paths without reading their contents.
 *
 * <p>If file's hash needs to be changed, for example to reflect changes to the file's contents, the
 * file's path can be specified in a set of modified files. Files specified in this set will get new
 * unique hashes based on their paths distinct from the hashes they would get if they were omitted
 * from the set.
 */
public class FilePathHashLoader implements FileHashLoader {

  private final Path defaultCellRoot;
  private final ImmutableSet<Path> assumeModifiedFiles;
  private final boolean allowSymlinks;

  public FilePathHashLoader(
      Path defaultCellRoot, ImmutableSet<Path> assumeModifiedFiles, boolean allowSymlinks)
      throws IOException {
    this.defaultCellRoot = defaultCellRoot;
    this.allowSymlinks = allowSymlinks;
    ImmutableSet.Builder<Path> modifiedFilesBuilder = ImmutableSet.builder();
    for (Path path : assumeModifiedFiles) {
      modifiedFilesBuilder.add(resolvePath(path));
    }
    this.assumeModifiedFiles = modifiedFilesBuilder.build();
  }

  @Override
  public HashCode get(Path root) throws IOException {
    // In case the root path is a directory, collect all files contained in it and sort them before
    // hashing to avoid non-deterministic directory traversal order from influencing the hash.
    ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    Files.walkFileTree(
        defaultCellRoot.resolve(root),
        ImmutableSet.of(FileVisitOption.FOLLOW_LINKS),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            files.add(file);
            return FileVisitResult.CONTINUE;
          }
        });
    Hasher hasher = Hashing.sha1().newHasher();
    for (Path file : files.build()) {
      file = resolvePath(file);
      boolean assumeModified = assumeModifiedFiles.contains(file);
      Path relativePath = MorePaths.relativize(defaultCellRoot, file);

      // For each file add its path to the hasher suffixed by whether we assume the file to be
      // modified or not. This way files with different paths always result in different hashes and
      // files that are assumed to be modified get different hashes than all unmodified files.
      StringHashing.hashStringAndLength(hasher, relativePath.toString());
      hasher.putBoolean(assumeModified);
    }
    return hasher.hash();
  }

  @Override
  public long getSize(Path path) throws IOException {
    return Files.size(path);
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) {
    throw new UnsupportedOperationException("Not implemented");
  }

  private Path resolvePath(Path path) throws IOException {
    Path resolvedPath = defaultCellRoot.resolve(path);
    if (!allowSymlinks) {
      return resolvedPath;
    }
    return resolvedPath.toRealPath();
  }
}
