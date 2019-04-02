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

package com.facebook.buck.util.cache;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.event.AbstractBuckEvent;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * This interface extracts the methods available to a file hash cache, so that the underlying
 * implementation is hidden and can be swapped.
 */
public interface FileHashCacheEngine {

  @FunctionalInterface
  interface ValueLoader<T> {
    T load(Path path);
  }

  void put(Path path, HashCodeAndFileType value);

  void putSize(Path path, long value);

  void invalidate(Path path);

  void invalidateWithParents(Path path);

  HashCode get(Path path) throws IOException;

  HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException;

  @Nullable
  HashCodeAndFileType getIfPresent(Path path);

  @Nullable
  Long getSizeIfPresent(Path path);

  long getSize(Path relativePath) throws IOException;

  void invalidateAll();

  ConcurrentMap<Path, HashCodeAndFileType> asMap();

  List<AbstractBuckEvent> getStatsEvents();
}
