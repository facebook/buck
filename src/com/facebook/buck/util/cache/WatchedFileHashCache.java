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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HashCodeAndFileType;
import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Map;

public class WatchedFileHashCache extends DefaultFileHashCache {

  private static final Logger LOG = Logger.get(WatchedFileHashCache.class);

  public WatchedFileHashCache(ProjectFilesystem projectFilesystem) {
    super(projectFilesystem);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required. {@link Path}s contained within events must all be relative to the
   * {@link ProjectFilesystem} root.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchEvent<?> event) throws IOException {
    if (getFilesystem().isPathChangeEvent(event)) {
      // Path event, remove the path from the cache as it has been changed, added or deleted.
      final Path path = ((Path) event.context()).normalize();
      LOG.verbose("Invalidating %s", path);
      Iterable<Path> pathsToInvalidate =
          Maps.filterEntries(
              loadingCache.asMap(),
              new Predicate<Map.Entry<Path, HashCodeAndFileType>>() {
                  @Override
                  public boolean apply(Map.Entry<Path, HashCodeAndFileType> entry) {
                    switch (entry.getValue().getType()) {
                      case FILE:
                        return path.equals(entry.getKey());
                      case DIRECTORY:
                        return path.startsWith(entry.getKey());
                    }
                    return false;
                  }
              }
          ).keySet();
      LOG.verbose("Paths to invalidate: %s", pathsToInvalidate);
      loadingCache.invalidateAll(pathsToInvalidate);
    } else {
      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      LOG.debug("Invalidating all");
      loadingCache.invalidateAll();
    }
  }

}
