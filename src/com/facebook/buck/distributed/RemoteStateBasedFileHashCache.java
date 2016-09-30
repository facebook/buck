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

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class RemoteStateBasedFileHashCache implements FileHashCache {
  private static final Function<BuildJobStateFileHashEntry, HashCode>
      HASH_CODE_FROM_FILE_HASH_ENTRY =
      new Function<BuildJobStateFileHashEntry, HashCode>() {
        @Override
        public HashCode apply(BuildJobStateFileHashEntry input) {
          return HashCode.fromString(input.getHashCode());
        }
      };

  private final Map<Path, HashCode> remoteFileHashes;
  private final Map<ArchiveMemberPath, HashCode> remoteArchiveHashes;

  public RemoteStateBasedFileHashCache(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    this.remoteFileHashes =
        Maps.transformValues(
            DistBuildFileHashes.indexEntriesByPath(projectFilesystem, remoteFileHashes),
            HASH_CODE_FROM_FILE_HASH_ENTRY);
    this.remoteArchiveHashes =
        Maps.transformValues(
            DistBuildFileHashes.indexEntriesByArchivePath(projectFilesystem, remoteFileHashes),
            HASH_CODE_FROM_FILE_HASH_ENTRY);
  }

  @Override
  public HashCode get(Path path) throws IOException {
    return Preconditions.checkNotNull(
        remoteFileHashes.get(path),
        "Path %s not in remote file hash.",
        path);
  }

  @Override
  public long getSize(Path path) throws IOException {
    return 0;
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    return Preconditions.checkNotNull(
        remoteArchiveHashes.get(archiveMemberPath),
        "Archive path %s not in remote file hash.",
        archiveMemberPath);
  }

  @Override
  public boolean willGet(Path path) {
    return remoteFileHashes.containsKey(path);
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    return remoteArchiveHashes.containsKey(archiveMemberPath);
  }

  @Override
  public void invalidate(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void invalidateAll() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void set(Path path, HashCode hashCode) throws IOException {
    throw new UnsupportedOperationException();
  }
}
