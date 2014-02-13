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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;


import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * A ProjectFilesystemWatcher implementation that uses the Java 7 WatchService.
 */
public class WatchServiceWatcher implements ProjectFilesystemWatcher {

  private final WatchService watchService;
  private final Map<WatchKey,Path> keys;
  private final EventBus eventBus;
  private final ProjectFilesystem filesystem;
  private final ImmutableSet<Path> ignoredPrefixes;

  public WatchServiceWatcher(ProjectFilesystem filesystem,
                             EventBus fileChangeEventBus,
                             ImmutableSet<Path> excludeDirectories,
                             WatchService watchService) throws IOException {
    this.filesystem = Preconditions.checkNotNull(filesystem);
    this.ignoredPrefixes = Preconditions.checkNotNull(excludeDirectories);
    this.eventBus = Preconditions.checkNotNull(fileChangeEventBus);
    this.watchService = Preconditions.checkNotNull(watchService);
    this.keys = Maps.newHashMap();
    registerAll(filesystem.getRootPath());
  }

  /** Post filesystem events to eventBus */
  @Override
  public void postEvents() throws IOException {
    WatchKey key;
    while((key = watchService.poll()) != null) {
      Path dir = keys.get(key);
      if (dir == null) {
        continue; // Ignored or unknown directory.
      }
      for (final WatchEvent<?> event : key.pollEvents()) {
        if (filesystem.isPathChangeEvent(event)) {

          // Check against ignored directories.
          Path name = (Path) event.context();
          Path absolutePath = dir.resolve(name);
          final Path projectRelativePath = filesystem.getRootPath().relativize(absolutePath);
          if (shouldIgnore(projectRelativePath)) {
            continue;
          }

          // If directory is created, watch its children.
          if (filesystem.isDirectory(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
              registerAll(absolutePath);
            }
            continue; // TODO(user): post events about directories?
          }

          // Path returned by event.context() is relative to key directory, so return resolved
          // child Path instead to allow clients to access the full, absolute Path correctly.
          eventBus.post(new WatchEvent<Path>(){

            @Override
            @SuppressWarnings("unchecked") // Needed for conversion from Kind<?> to Kind<Path>
            public Kind<Path> kind() {
              return (Kind<Path>) event.kind();
            }

            @Override
            public int count() {
              return event.count();
            }

            @Override
            public Path context() {
              return projectRelativePath;
            }
          });

        } else {
          eventBus.post(event);
        }
      }

      // Reset key and remove from set if directory no longer accessible
      if (!key.reset()) {
        keys.remove(key);
      }
    }
  }

  private boolean shouldIgnore(Path path) {
    Path normalizedPath = path.normalize();
    for (Path prefix : ignoredPrefixes) {
      if (normalizedPath.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Register the given directory with the WatchService.
   */
  private void register(Path dir) throws IOException {
    WatchKey key = dir.register(watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY);
    keys.put(key, dir);
  }

  /**
   * Register the given directory, and all its sub-directories, with the
   * WatchService, unless it's an ignored sub-tree.
   */
  private void registerAll(final Path start) throws IOException {
    filesystem.walkFileTree(start, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
          throws IOException {
        if (shouldIgnore(dir)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        register(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  @Override
  public void close() throws IOException {
    watchService.close();
  }
}
