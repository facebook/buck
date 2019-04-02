/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** FileHashCache that is populated with a fixed set of entries. */
public class FakeProjectFileHashCache implements ProjectFileHashCache {

  private final ProjectFilesystem filesystem;
  private final Map<Path, HashCode> pathsToHashes;

  public FakeProjectFileHashCache(ProjectFilesystem filesystem, Map<Path, HashCode> pathsToHashes) {
    this.filesystem = filesystem;
    this.pathsToHashes = pathsToHashes;
  }

  public static FakeProjectFileHashCache createFromStrings(
      ProjectFilesystem filesystem, Map<String, String> pathsToHashes) {
    Map<Path, HashCode> cachedValues = new HashMap<>();
    for (Map.Entry<String, String> entry : pathsToHashes.entrySet()) {
      cachedValues.put(filesystem.getPath(entry.getKey()), HashCode.fromString(entry.getValue()));
    }
    return new FakeProjectFileHashCache(filesystem, cachedValues);
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  @Override
  public boolean willGet(Path path) {
    return pathsToHashes.containsKey(path);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    return false;
  }

  @Override
  public boolean isIgnored(Path path) {
    return false;
  }

  @Override
  public void invalidate(Path path) {
    pathsToHashes.remove(path);
  }

  @Override
  public void invalidateAll() {
    pathsToHashes.clear();
  }

  @Override
  public HashCode get(Path path) throws IOException {
    HashCode hashCode = pathsToHashes.get(path);
    if (hashCode == null) {
      throw new NoSuchFileException(path.toString());
    }
    return hashCode;
  }

  @Override
  public long getSize(Path path) throws IOException {
    throw new NoSuchFileException(path.toString());
  }

  @Override
  public Optional<HashCode> getIfPresent(Path path) {
    return Optional.ofNullable(pathsToHashes.get(path));
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    throw new NoSuchFileException(archiveMemberPath.toString());
  }

  @Override
  public void set(Path path, HashCode hashCode) {
    pathsToHashes.put(path, hashCode);
  }

  public boolean contains(Path path) {
    return pathsToHashes.containsKey(path);
  }
}
