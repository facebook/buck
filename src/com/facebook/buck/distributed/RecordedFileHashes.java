/*
 * Copyright 2017-present Facebook, Inc.
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
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class RecordedFileHashes {
  private final BuildJobStateFileHashes remoteFileHashes;
  private final Set<Path> seenPaths;
  private final Set<ArchiveMemberPath> seenArchiveMemberPaths;

  public RecordedFileHashes(Integer cellIndex) {
    this.remoteFileHashes = new BuildJobStateFileHashes();
    this.remoteFileHashes.setCellIndex(cellIndex);
    this.seenPaths = new HashSet<>();
    this.seenArchiveMemberPaths = new HashSet<>();
  }

  public synchronized boolean containsAndAddPath(Path relPath) {
    return !seenPaths.add(relPath);
  }

  public synchronized boolean containsAndAddPath(ArchiveMemberPath relPath) {
    return seenArchiveMemberPaths.contains(relPath);
  }

  public synchronized void addEntry(BuildJobStateFileHashEntry entry) {
    remoteFileHashes.addToEntries(entry);
  }

  public BuildJobStateFileHashes getRemoteFileHashes() {
    return remoteFileHashes;
  }
}
