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

package com.facebook.buck.util.cache;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * A cache which maps Paths to cached hashes of their contents, based on a simplified subset of the
 * java.util.Map&lt;Path, HashCode&gt; interface.
 */
public interface FileHashCache extends FileHashLoader {

  void invalidate(Path path);

  void invalidateAll();

  void set(Path path, HashCode hashCode) throws IOException;

  /**
   * Set the {@link HashCode} for the given relative {@link Path} under the given {@link
   * ProjectFilesystem}.
   */
  default void set(ProjectFilesystem filesystem, Path path, HashCode hashCode) throws IOException {
    set(filesystem.resolve(path), hashCode);
  }

  default FileHashCacheVerificationResult verify() throws IOException {
    throw new RuntimeException(
        "FileHashCache class " + getClass().getName() + " does not support verification.");
  }

  default Stream<Map.Entry<Path, HashCode>> debugDump() {
    throw new RuntimeException(
        "FileHashCache class " + getClass().getName() + " does not support debugDump.");
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractFileHashCacheVerificationResult {
    int getCachesExamined();

    int getFilesExamined();

    List<String> getVerificationErrors();
  }
}
