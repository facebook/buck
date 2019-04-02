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

package com.facebook.buck.util.cache;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.hashing.ProjectFileHashLoader;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.stream.Stream;

/**
 * A {@link FileHashLoader} which manages caching file hashes for a given {@link ProjectFilesystem}.
 */
public interface ProjectFileHashCache extends ProjectFileHashLoader {

  ProjectFilesystem getFilesystem();

  boolean willGet(Path path);

  boolean willGet(ArchiveMemberPath archiveMemberPath);

  boolean isIgnored(Path path);

  void invalidate(Path path);

  void invalidateAll();

  void set(Path path, HashCode hashCode) throws IOException;

  default FileHashCacheVerificationResult verify() throws IOException {
    throw new RuntimeException(
        "ProjectFileHashCache class " + getClass().getName() + " does not support verification.");
  }

  default Stream<Entry<Path, HashCode>> debugDump() {
    throw new RuntimeException(
        "ProjectFileHashCache class " + getClass().getName() + " does not support debugDump.");
  }
}
