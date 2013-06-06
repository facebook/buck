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

import java.io.Closeable;
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
 * Watches a ProjectFilesystem for file changes using a given WatchService.
 * Change events are posted to a given EventBus when postEvents are called unless the affected files are
 * contained within the given excludeDirectories.
 */
public class ProjectFilesystemWatcher implements Closeable {

  private final WatchService watchService; // TODO(user): use intellij file watching?
  private final Map<WatchKey,Path> keys;
  private final EventBus eventBus;
  private final ProjectFilesystem filesystem;
  private final ImmutableSet<String> ignoredPrefixes;

  public ProjectFilesystemWatcher(ProjectFilesystem filesystem,
                                  EventBus fileChangeEventBus,
                                  ImmutableSet<String> excludeDirectories,
                                  WatchService watchService) throws IOException {
    this.filesystem = Preconditions.checkNotNull(filesystem);
    this.ignoredPrefixes = Preconditions.checkNotNull(excludeDirectories);
    this.eventBus = Preconditions.checkNotNull(fileChangeEventBus);
    this.watchService = Preconditions.checkNotNull(watchService);
    this.keys = Maps.newHashMap();
    registerAll(filesystem.getProjectRoot().toPath());
  }

  /** Post filesystem events to eventBus */
  public void postEvents() throws IOException {
    WatchKey key;
    while((key = watchService.poll()) != null) {
      Path dir = keys.get(key);
      if (dir == null) {
        continue; // Ignored or unknown directory.
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        if (filesystem.isPathChangeEvent(event)) {
          Path name = (Path) event.context();
          Path child = dir.resolve(name);
          if (shouldIgnore(child)) {
            continue;
          }
          if (filesystem.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
              registerAll(child);
            }
            continue; // TODO(user): post events about directories?
          }
        }
        eventBus.post(event);
      }

      // Reset key and remove from set if directory no longer accessible
      if (!key.reset()) {
        keys.remove(key);
      }
    }
  }

  private boolean shouldIgnore(Path path) {
    Path normalizedPath = path.normalize();
    for (String prefix : ignoredPrefixes) {
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
