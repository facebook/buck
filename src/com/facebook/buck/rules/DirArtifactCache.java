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

package com.facebook.buck.rules;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.facebook.buck.util.MoreFiles;
import com.facebook.buck.util.collect.ArrayIterable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DirArtifactCache implements ArtifactCache {

  private final static Logger logger = Logger.getLogger(DirArtifactCache.class.getName());

  private final File cacheDir;
  private final Optional<Long> maxCacheSizeBytes;

  public DirArtifactCache(File cacheDir, Optional<Long> maxCacheSizeBytes) throws IOException {
    this.cacheDir = Preconditions.checkNotNull(cacheDir);
    this.maxCacheSizeBytes = Preconditions.checkNotNull(maxCacheSizeBytes);
    Files.createDirectories(cacheDir.toPath());
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) {
    CacheResult success = CacheResult.MISS;
    File cacheEntry = new File(cacheDir, ruleKey.toString());
    if (cacheEntry.exists()) {
      try {
        Files.createDirectories(output.toPath().getParent());
        Files.copy(cacheEntry.toPath(), output.toPath(), REPLACE_EXISTING);
        success = CacheResult.DIR_HIT;
      } catch (IOException e) {
        logger.warning(String.format("Artifact fetch(%s, %s) error: %s",
            ruleKey,
            output.getPath(),
            e.getMessage()));
      }
    }
    logger.info(String.format("Artifact fetch(%s, %s) cache %s",
        ruleKey,
        output.getPath(),
        (success.isSuccess() ? "hit" : "miss")));
    return success;
  }

  @Override
  public void store(RuleKey ruleKey, File output) {
    File cacheEntry = new File(cacheDir, ruleKey.toString());
    Path tmpCacheEntry = null;
    try {
      // Write to a temporary file and move the file to its final location atomically to protect
      // against partial artifacts (whether due to buck interruption or filesystem failure) posing
      // as valid artifacts during subsequent buck runs.
      tmpCacheEntry = File.createTempFile(ruleKey.toString(), ".tmp", cacheDir).toPath();
      Files.copy(output.toPath(), tmpCacheEntry, REPLACE_EXISTING);
      Files.move(tmpCacheEntry, cacheEntry.toPath());
    } catch (IOException e) {
      logger.warning(String.format("Artifact store(%s, %s) error: %s",
          ruleKey,
          output.getPath(),
          e.getMessage()));
      if (tmpCacheEntry != null) {
        try {
          Files.deleteIfExists(tmpCacheEntry);
        } catch (IOException ignored) {
          // Unable to delete a temporary file. Nothing sane to do.
          logger.log(Level.INFO, "Unable to delete temp cache file", ignored);
        }
      }
    }
  }

  /**
   * @return {@code true}: storing artifacts is always supported by this class.
   */
  @Override
  public boolean isStoreSupported() {
    return true;
  }

  @Override
  public void close() {
    // store() operation is synchronous - do nothing.
  }

  /**
   * @param finished Signals that the build has finished.
   */
  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    deleteOldFiles();
  }

  /**
   * Deletes files that haven't been accessed recently from the directory cache.
   */
  @VisibleForTesting
  void deleteOldFiles() {
    if (!maxCacheSizeBytes.isPresent()) {
      return;
    }
    for (File fileAccessedEntry : findFilesToDelete()) {
      try {
        Files.deleteIfExists(fileAccessedEntry.toPath());
      } catch (IOException e) {
        // Eat any IOExceptions while attempting to clean up the cache directory.  If the file is
        // now in use, we no longer want to delete it.
        continue;
      }
    }
  }

  private Iterable<File> findFilesToDelete() {
    Preconditions.checkState(maxCacheSizeBytes.isPresent());
    long maxSizeBytes = maxCacheSizeBytes.get();

    File[] files = cacheDir.listFiles();
    MoreFiles.sortFilesByAccessTime(files);

    // Finds the first N from the list ordered by last access time who's combined size is less than
    // maxCacheSizeBytes.
    long currentSizeBytes = 0;
    for (int i = 0; i < files.length; ++i) {
      File file = files[i];
      currentSizeBytes += file.length();
      if (currentSizeBytes > maxSizeBytes) {
        return ArrayIterable.of(files, i, files.length);
      }
    }
    return ImmutableList.<File>of();
  }
}
