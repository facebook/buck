/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** A local filesystem backed implementation for the {@link ParserCacheStorage} interface. */
public class LocalCacheStorage implements ParserCacheStorage {
  private static final Logger LOG = Logger.get(LocalCacheStorage.class);

  private final Path localCachePath;
  private final ProjectFilesystem filesystem;
  private final ParserCacheAccessMode cacheAccessMode;

  private LocalCacheStorage(
      Path localCachePath, ParserCacheAccessMode cacheAccessMode, ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    this.localCachePath = localCachePath;
    this.cacheAccessMode = cacheAccessMode;

    // create local cache folde if it does not exist
    if ((cacheAccessMode == ParserCacheAccessMode.WRITEONLY
            || cacheAccessMode == ParserCacheAccessMode.READWRITE)
        && !filesystem.exists(localCachePath)) {
      try {
        filesystem.mkdirs(localCachePath);
      } catch (IOException ex) {
        // TODO(buck_team): make checked exception propagate
        throw new BuckUncheckedExecutionException(
            ex, "When creating local cache directory %s.", localCachePath);
      }
    }
  }

  private boolean isReadAllowed() {
    return cacheAccessMode.isReadable();
  }

  private boolean isWriteAllowed() {
    return cacheAccessMode.isWritable();
  }

  private static ParserCacheAccessMode obtainLocalCacheStorageAccessModeFromConfig(
      AbstractParserCacheConfig parserCacheConfig) {
    return parserCacheConfig.getDirCacheAccessMode();
  }

  /**
   * Static factory for creating {@link LocalCacheStorage} objects.
   *
   * @param parserCacheConfig the {@code parserCacheConfig} object to be used for this parsing.
   * @return a new instance of fully instantiated local cache object.
   * @throws ParserCacheException when the {@link LocalCacheStorage} object cannot be constructed.
   */
  public static LocalCacheStorage of(
      AbstractParserCacheConfig parserCacheConfig, ProjectFilesystem filesystem) {
    Preconditions.checkState(
        parserCacheConfig.isDirParserCacheEnabled(),
        "Invalid state: LocalCacheStorage should not be instantiated if the cache is disabled.");

    Preconditions.checkState(
        parserCacheConfig.getDirCacheLocation().isPresent(), "Dir cache location is not set!");

    Path localCachePath = parserCacheConfig.getDirCacheLocation().get();

    ParserCacheAccessMode cacheAccessMode =
        obtainLocalCacheStorageAccessModeFromConfig(parserCacheConfig);
    return new LocalCacheStorage(localCachePath, cacheAccessMode, filesystem);
  }

  @Override
  public void storeBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint, byte[] serializedBuildFileManifest)
      throws IOException {

    Stopwatch timer = null;
    if (LOG.isDebugEnabled()) {
      timer = Stopwatch.createStarted();
    }

    try {
      if (!isWriteAllowed()) {
        return;
      }

      Path weakFingerprintCachePath = getOrCreateWeakFingerprintFolder(weakFingerprint);

      Path cachedBuildFileManifestPath =
          weakFingerprintCachePath.resolve(strongFingerprint.toString());

      Path relativePathToRoot =
          cachedBuildFileManifestPath.isAbsolute()
              ? filesystem.getRootPath().relativize(cachedBuildFileManifestPath)
              : cachedBuildFileManifestPath;

      try (OutputStream fw = filesystem.newFileOutputStream(relativePathToRoot)) {
        fw.write(serializedBuildFileManifest);
      }
    } finally {
      if (timer != null) {
        LOG.debug(
            "Time to complete storeBuildFileManifest: %d ns.",
            timer.stop().elapsed(TimeUnit.NANOSECONDS));
      }
    }
  }

  /** @return Path to a weak fingerprint folder, creating one if it does not exist */
  private Path getOrCreateWeakFingerprintFolder(HashCode weakFingerprint) throws IOException {
    Path weakFingerprintCachePath = localCachePath.resolve(weakFingerprint.toString());

    if (!filesystem.exists(weakFingerprintCachePath)) {
      filesystem.mkdirs(weakFingerprintCachePath);
    }
    return weakFingerprintCachePath;
  }

  @Override
  public Optional<BuildFileManifest> getBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint) throws IOException {
    Stopwatch timer = null;
    if (LOG.isDebugEnabled()) {
      timer = Stopwatch.createStarted();
    }

    try {
      if (!isReadAllowed()) {
        return Optional.empty();
      }

      Path weakFingerprintCachePath = localCachePath.resolve(weakFingerprint.toString());

      if (!filesystem.exists(weakFingerprintCachePath)) {
        return Optional.empty();
      }

      byte[] deserializedBuildFileManifest =
          deserializeBuildFileManifest(strongFingerprint, weakFingerprintCachePath);

      return Optional.of(BuildFileManifestSerializer.deserialize(deserializedBuildFileManifest));
    } finally {
      if (timer != null) {
        LOG.debug(
            "Time to complete getBuildFileManifest: %d ns.",
            timer.stop().elapsed(TimeUnit.NANOSECONDS));
      }
    }
  }

  @Override
  public void deleteCacheEntries(HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException {
    if (!isWriteAllowed()) {
      return;
    }
    Path weakFingerprintCachePath = localCachePath.resolve(weakFingerprint.toString());
    filesystem.deleteRecursivelyIfExists(
        weakFingerprintCachePath.isAbsolute()
            ? filesystem.getRootPath().relativize(weakFingerprintCachePath)
            : weakFingerprintCachePath);
  }

  private byte[] deserializeBuildFileManifest(
      HashCode strongFingerprint, Path weakFingerprintCachePath) throws IOException {
    byte[] deserializedBuildFileManifest;
    Path cachedBuildFileManifestPath =
        weakFingerprintCachePath.resolve(strongFingerprint.toString());
    try (InputStream fis = filesystem.newFileInputStream(cachedBuildFileManifestPath)) {
      deserializedBuildFileManifest =
          new byte[(int) filesystem.getFileSize(cachedBuildFileManifestPath)];
      fis.read(deserializedBuildFileManifest);
    }
    return deserializedBuildFileManifest;
  }
}
