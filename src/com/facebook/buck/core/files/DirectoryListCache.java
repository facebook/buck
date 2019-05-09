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
import com.facebook.buck.event.FileHashCacheEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
    private Set<Path> foldersWithDeletedFiles = new HashSet<>();

    private Invalidator(DirectoryListCache dirListCache, Path rootPath) {
      this.dirListCache = dirListCache;
      this.rootPath = rootPath;
    }

    /** Executes when invalidation is about to start */
    @Subscribe
    @SuppressWarnings("unused")
    public void onInvalidationStart(FileHashCacheEvent.InvalidationStarted event) {
      // reinstantiate just in case
      foldersWithDeletedFiles = new HashSet<>();
    }

    /** Executes when all invalidation events were sent */
    @Subscribe
    @SuppressWarnings("unused")
    public void onInvalidationFinish(FileHashCacheEvent.InvalidationFinished event) {
      // TODO(sergeyb): replace with an event that sends all changed paths at once

      // Check if a folder was entirely deleted, in which case invalidate also parent folders
      // recursively
      if (foldersWithDeletedFiles.isEmpty()) {
        return;
      }

      HashSet<Path> deletedFolders = new HashSet<>();
      HashSet<Path> existingFolders = new HashSet<>();

      // First, build a list of folders that contain subfolders which were really deleted
      // Do it recursively traversing folder structure up
      for (Path folder : foldersWithDeletedFiles) {
        findDeletedFolders(folder, deletedFolders, existingFolders);
      }

      // Then invalidate those paths
      for (Path folder : deletedFolders) {
        dirListCache.cache.remove(ImmutableDirectoryListKey.of(MorePaths.getParentOrEmpty(folder)));
      }

      foldersWithDeletedFiles = new HashSet<>();
    }

    private void findDeletedFolders(
        Path folder, Set<Path> deletedFolders, Set<Path> existingFolders) {
      if (deletedFolders.contains(folder) || existingFolders.contains(folder)) {
        // avoid expensive filesystem operation if folder was already processed by some other
        // codepath
        return;
      }

      if (Files.exists(rootPath.resolve(folder))) {
        // folder was not actually deleted, no need to invalidate its parents
        existingFolders.add(folder);
        return;
      }

      deletedFolders.add(folder);

      if (MorePaths.isEmpty(folder)) {
        // this is root, stop recursing
        return;
      }

      // recurse up the tree
      findDeletedFolders(MorePaths.getParentOrEmpty(folder), deletedFolders, existingFolders);
    }

    /** Invoked asynchronously by event bus when file system change is detected with Watchman */
    @Subscribe
    public void onFileSystemChange(WatchmanPathEvent event) {
      if (event.getKind() == Kind.MODIFY) {
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

      if (event.getKind() == Kind.DELETE) {
        // Watchman does not report when a folder is deleted, it reports deletions of all the files
        // in that folder. If a folder is deleted, we have to invalidate also containing
        // parent DirectoryList. So we keep track of all affected folders in order to possibly
        // invalidate their parents.
        foldersWithDeletedFiles.add(folderPath);
      }
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
