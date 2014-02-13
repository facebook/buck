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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.concurrent.ExecutionException;

public class DefaultFileHashCache implements FileHashCache {

  private final ProjectFilesystem projectFilesystem;
  private Console console;

  @VisibleForTesting
  final LoadingCache<Path, HashCode> loadingCache;

  public DefaultFileHashCache(ProjectFilesystem projectFilesystem, Console console) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.console = Preconditions.checkNotNull(console);

    this.loadingCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<Path, HashCode>() {
          @Override
          public HashCode load(Path path) throws Exception {
            File file = DefaultFileHashCache.this.projectFilesystem.resolve(path).toFile();
            InputSupplier<? extends InputStream> inputSupplier = Files.newInputStreamSupplier(file);
            return ByteStreams.hash(inputSupplier, Hashing.sha1());
          }
        });
  }

  @Override
  public boolean contains(Path path) {
    return loadingCache.getIfPresent(path) != null;
  }

  /**
   * @param path {@link #contains(java.nio.file.Path)} must be true for path.
   * @return The {@link com.google.common.hash.HashCode} of the contents of path.
   */
  @Override
  public HashCode get(Path path) {
    HashCode sha1;
    try {
      sha1 = loadingCache.get(path.normalize());
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    return Preconditions.checkNotNull(sha1, "Failed to find a HashCode for %s.", path);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required. {@link Path}s contained within events must all be relative to the
   * {@link ProjectFilesystem} root.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchEvent<?> event) throws IOException {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("DefaultFileHashCache watched event %s %s\n", event.kind(),
          projectFilesystem.createContextString(event));
    }

    if (projectFilesystem.isPathChangeEvent(event)) {
      // Path event, remove the path from the cache as it has been changed, added or deleted.
      Path path = (Path) event.context();
      loadingCache.invalidate(path.normalize());
    } else {
      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      loadingCache.invalidateAll();
    }
  }

  /**
   * DefaultFileHashCaches may be reused on different consoles, so allow the console to be set.
   * @param console The new console that the Parser should use.
   */
  public synchronized void setConsole(Console console) {
    this.console = console;
  }
}
