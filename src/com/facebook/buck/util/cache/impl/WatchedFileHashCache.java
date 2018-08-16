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

package com.facebook.buck.util.cache.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;

public class WatchedFileHashCache extends DefaultFileHashCache {

  private static final Logger LOG = Logger.get(WatchedFileHashCache.class);

  public WatchedFileHashCache(
      ProjectFilesystem projectFilesystem, FileHashCacheMode fileHashCacheMode) {
    super(projectFilesystem, getDefaultPathPredicate(projectFilesystem), fileHashCacheMode);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required. {@link Path}s contained within events must all be relative to the
   * {@link ProjectFilesystem} root.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchmanPathEvent event) {
    // Path event, remove the path from the cache as it has been changed, added or deleted.
    Path path = event.getPath().normalize();
    LOG.verbose("Invalidating %s", path);
    fileHashCacheEngine.invalidateWithParents(path);
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void onFileSystemChange(WatchmanOverflowEvent event) {
    // Non-path change event, likely an overflow due to many change events: invalidate everything.
    LOG.debug("Invalidating all");
    invalidateAll();
  }
}
