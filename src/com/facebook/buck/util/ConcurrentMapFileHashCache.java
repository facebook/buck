/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.WatchEvent;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;

public class ConcurrentMapFileHashCache implements FileHashCache {

  private final ConcurrentMap<Path, HashCode> hashCache;
  private final ProjectFilesystem projectFilesystem;
  private final Console console;

  public ConcurrentMapFileHashCache(ProjectFilesystem projectFilesystem, Console console) {
    this.projectFilesystem = projectFilesystem;
    this.console = console;
    hashCache = Maps.newConcurrentMap();
  }

  @Override
  public boolean contains(Path path) {
    return hashCache.containsKey(path);
  }

  /**
   * @param path {@link #contains(java.nio.file.Path)} must be true for path.
   * @return The {@link com.google.common.hash.HashCode} of the contents of path.
   */
  @Override
  public HashCode get(Path path) {

    // checkNotNull on result rather than checkState(this.contains(Path)) to avoid 2 lookups.
    return Preconditions.checkNotNull(hashCache.get(path));
  }

  @Override
  public void put(Path path, HashCode fileSha1) {
    hashCache.put(path, fileSha1);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required.
   */
  @Subscribe
  public void onFileSystemChange(WatchEvent<?> event) throws IOException {

    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("ConcurrentMapFileHashCache watched event %s %s\n", event.kind(),
          projectFilesystem.createContextString(event));
    }

    if (projectFilesystem.isPathChangeEvent(event)) {

      // Path event, remove the path from the cache as it has been changed, added or deleted.
      Path path = (Path) event.context();
      hashCache.remove(path);

    } else {

      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      hashCache.clear();
    }
  }
}
