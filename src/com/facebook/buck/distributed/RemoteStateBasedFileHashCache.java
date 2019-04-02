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

package com.facebook.buck.distributed;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class RemoteStateBasedFileHashCache implements ProjectFileHashCache {
  private static final Function<BuildJobStateFileHashEntry, HashCode>
      HASH_CODE_FROM_FILE_HASH_ENTRY =
          input -> {
            if (input.getSha1() == null) {
              return null;
            }
            return HashCode.fromString(input.getSha1());
          };

  private final ProjectFileHashCache delegate;
  private final ProjectFilesystem filesystem;
  private final Map<Path, HashCode> remoteFileHashes;
  private final Map<ArchiveMemberPath, HashCode> remoteArchiveHashes;

  public RemoteStateBasedFileHashCache(
      ProjectFileHashCache delegate, BuildJobStateFileHashes remoteFileHashes) {
    this.delegate = delegate;
    this.filesystem = delegate.getFilesystem();
    this.remoteFileHashes =
        Maps.transformValues(
            DistBuildFileHashes.indexEntriesByPath(filesystem, remoteFileHashes),
            HASH_CODE_FROM_FILE_HASH_ENTRY::apply);
    this.remoteArchiveHashes =
        Maps.transformValues(
            DistBuildFileHashes.indexEntriesByArchivePath(filesystem, remoteFileHashes),
            HASH_CODE_FROM_FILE_HASH_ENTRY::apply);
  }

  @Override
  public HashCode get(Path relPath) throws IOException {
    HashCode hashCode = remoteFileHashes.get(filesystem.resolve(relPath));
    if (hashCode != null) {
      return hashCode;
    }

    return delegate.get(relPath);
  }

  @Override
  public long getSize(Path relPath) throws IOException {
    return delegate.getSize(relPath);
  }

  @Override
  public Optional<HashCode> getIfPresent(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberRelPath) throws IOException {
    HashCode hashCode =
        remoteArchiveHashes.get(
            archiveMemberRelPath.withArchivePath(
                filesystem.resolve(archiveMemberRelPath.getArchivePath())));
    if (hashCode != null) {
      return hashCode;
    }

    return delegate.get(archiveMemberRelPath);
  }

  @Override
  public boolean willGet(Path relPath) {
    return remoteFileHashes.containsKey(filesystem.resolve(relPath)) || delegate.willGet(relPath);
  }

  @Override
  public boolean willGet(ArchiveMemberPath relPath) {
    return remoteArchiveHashes.containsKey(
            relPath.withArchivePath(filesystem.resolve(relPath.getArchivePath())))
        || delegate.willGet(relPath);
  }

  @Override
  public void invalidate(Path relPath) {
    delegate.invalidate(relPath);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public void set(Path relPath, HashCode hashCode) throws IOException {
    delegate.set(relPath, hashCode);
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  @Override
  public boolean isIgnored(Path path) {
    return delegate.isIgnored(path);
  }
}
