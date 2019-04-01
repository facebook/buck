/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores a list of files and subfolders per each folder */
public class DirectoryListCache implements GraphEngineCache<DirectoryListKey, DirectoryList> {

  private ConcurrentHashMap<DirectoryListKey, DirectoryList> cache = new ConcurrentHashMap<>();
  private final Invalidator invalidator;

  private DirectoryListCache(Path rootPath) {
    this.invalidator = new Invalidator(this, rootPath);
  }

  /**
   * Create a new instance of {@link DirectoryListCache}
   *
   * @param rootPath Absolute path to the root folder for which files and subfolders are cached
   */
  public static DirectoryListCache of(Path rootPath) {
    return new DirectoryListCache(rootPath);
  }

  @Override
  public Optional<DirectoryList> get(DirectoryListKey key) {
    return Optional.ofNullable(cache.get(key));
  }

  @Override
  public void put(DirectoryListKey key, DirectoryList directoryList) {
    cache.put(key, directoryList);
  }

  /** @return class that listens to watchman events and invalidates internal cache state */
  public Invalidator getInvalidator() {
    return invalidator;
  }

  /**
   * Subscribes to watchman event and invalidates internal state of a provided {@link
   * DirectoryListCache}
   */
  public static class Invalidator {

    private final DirectoryListCache dirListCache;
    private final Path rootPath;

    private Invalidator(DirectoryListCache dirListCache, Path rootPath) {
      this.dirListCache = dirListCache;
      this.rootPath = rootPath;
    }

    /** Invoked asynchronously by event bus when file system change is detected with Watchman */
    @Subscribe
    public void onFileSystemChange(WatchmanPathEvent event) {
      if (event.getKind() == WatchmanPathEvent.Kind.MODIFY) {
        // file modifications do not change directory structure, do nothing
        return;
      }

      if (!rootPath.equals(event.getCellPath())) {
        // must be same cell
        return;
      }

      // for CREATE and DELETE, invalidate containing folder
      Path folderPath = MorePaths.getParentOrEmpty(event.getPath());
      DirectoryListKey key = ImmutableDirectoryListKey.of(folderPath);
      dirListCache.cache.remove(key);
    }

    /**
     * Invoked asynchronously by event bus when Watchman detects too many files changed or unable to
     * detect changes, this should drop the cache
     */
    @Subscribe
    @SuppressWarnings("unused")
    public void onFileSystemChange(WatchmanOverflowEvent event) {
      dirListCache.cache = new ConcurrentHashMap<>();
    }
  }
}
