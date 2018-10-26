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
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** An local filesystem backed implementation for the {@link ParserCacheStorage} interface. */
public class LocalCacheStorage implements ParserCacheStorage {
  private static final Logger LOG = Logger.get(LocalCacheStorage.class);

  private final Path localCachePath;
  private final ProjectFilesystem filesystem;

  private LocalCacheStorage(Path localCachePath, ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    this.localCachePath = localCachePath;
  }

  private static Path createLocalCachePathFromConfig(
      AbstractParserCacheConfig parserCacheConfig, ProjectFilesystem filesystem)
      throws ParserCacheException {
    // Set the local parser state - create directory structure etc.
    Preconditions.checkState(
        parserCacheConfig.getDirCacheLocation().isPresent(), "Dir cache location is not set!");

    Path cachePath = parserCacheConfig.getDirCacheLocation().get();

    try {
      filesystem.createParentDirs(cachePath);
      LOG.info("Created parser cache directory: %s.", cachePath);
    } catch (IOException t) {
      LOG.info(t, "Failed to create parser cache directory: %s.", cachePath);
      throw new ParserCacheException(t, "Failed to create local cache directory - %s", cachePath);
    }

    return cachePath;
  }

  /**
   * Static factory for creating {@link LocalCacheStorage} objects.
   *
   * @param parserCacheConfig the {@code parserCacheConfig} object to be used for this parsing.
   * @return a new instance of fully instantiated local cache object.
   * @throws ParserCacheException when the {@link LocalCacheStorage} object cannot be constructed.
   */
  public static LocalCacheStorage newInstance(
      AbstractParserCacheConfig parserCacheConfig, ProjectFilesystem filesystem)
      throws ParserCacheException {
    Preconditions.checkState(
        parserCacheConfig.isDirParserCacheEnabled(),
        "Invalid state: LocalCacheStorage should not be instantiated if the cache is disabled.");
    Path localCachePath = createLocalCachePathFromConfig(parserCacheConfig, filesystem);
    return new LocalCacheStorage(localCachePath, filesystem);
  }

  @Override
  public void storeBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint, BuildFileManifest buildFileManifest)
      throws ParserCacheException {
    Stopwatch timer = Stopwatch.createStarted();

    try {
      Path weakFingerprintCachePath = createWeakFingerprintFolder(weakFingerprint);

      byte[] serializedBuildFileManifest =
          serializeBuildFileManifestToBytes(buildFileManifest, weakFingerprintCachePath);

      storeBuildFileManifestToCache(
          strongFingerprint, weakFingerprintCachePath, serializedBuildFileManifest);
    } finally {
      LOG.debug(
          "Time to complete storeBuildFileManifest: %d.",
          timer.stop().elapsed(TimeUnit.NANOSECONDS));
    }
  }

  private void storeBuildFileManifestToCache(
      HashCode strongFingerprint, Path weakFingerprintCachePath, byte[] serializedBuildFileManifest)
      throws ParserCacheException {
    Path cachedBuildFileManifestPath =
        weakFingerprintCachePath.resolve(strongFingerprint.toString());

    Path relativePathToRoot =
        cachedBuildFileManifestPath.isAbsolute()
            ? filesystem.getRootPath().relativize(cachedBuildFileManifestPath)
            : cachedBuildFileManifestPath;

    try (OutputStream fw = filesystem.newFileOutputStream(relativePathToRoot)) {
      fw.write(serializedBuildFileManifest);
    } catch (IOException t) {
      throw new ParserCacheException(
          t, "Failed to store BuildFileManifgest to file %s.", weakFingerprintCachePath);
    }
  }

  private byte[] serializeBuildFileManifestToBytes(
      BuildFileManifest buildFileManifest, Path weakFingerprintCachePath)
      throws ParserCacheException {
    byte[] serializedBuildFileManifest;
    try {
      serializedBuildFileManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
    } catch (IOException e) {
      throw new ParserCacheException(
          e, "Failed to serialize BuildFileManifgest - path %s.", weakFingerprintCachePath);
    }
    return serializedBuildFileManifest;
  }

  private Path createWeakFingerprintFolder(HashCode weakFingerprint) throws ParserCacheException {
    Path weakFingerprintCachePath = localCachePath.resolve(weakFingerprint.toString());

    if (!filesystem.exists(weakFingerprintCachePath)) {
      try {
        filesystem.mkdirs(weakFingerprintCachePath);
      } catch (IOException t) {
        throw new ParserCacheException(
            t, "Cannot create WeakFingerPrintFolder: %s.", weakFingerprintCachePath);
      }
    }
    return weakFingerprintCachePath;
  }

  @Override
  public BuildFileManifest getBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint) throws ParserCacheException {
    Stopwatch timer = Stopwatch.createStarted();
    try {
      Path weakFingerprintCachePath = getWeakFingerprintPath(weakFingerprint);

      byte[] deserializedBuildFileManifest =
          deserializeBuildFileManifest(strongFingerprint, weakFingerprintCachePath);

      return getBuildFileManifestFromDesirealizedBytes(deserializedBuildFileManifest);
    } finally {
      LOG.debug(
          "Time to complete getBuildFileManifest: %d.", timer.stop().elapsed(TimeUnit.NANOSECONDS));
    }
  }

  private BuildFileManifest getBuildFileManifestFromDesirealizedBytes(
      byte[] deserializedBuildFileManifest) throws ParserCacheException {
    BuildFileManifest buildFileManifest;
    try {
      buildFileManifest = BuildFileManifestSerializer.deserialize(deserializedBuildFileManifest);
    } catch (IOException t) {
      throw new ParserCacheException(
          t,
          "Failed to deserialize manifest for BuildFileManifgest from file %s.",
          deserializedBuildFileManifest);
    }
    return buildFileManifest;
  }

  private byte[] deserializeBuildFileManifest(
      HashCode strongFingerprint, Path weakFingerprintCachePath) throws ParserCacheException {
    byte[] deserializedBuildFileManifest;
    Path cachedBuildFileManifestPath =
        weakFingerprintCachePath.resolve(strongFingerprint.toString());
    try (InputStream fis = filesystem.newFileInputStream(cachedBuildFileManifestPath)) {
      deserializedBuildFileManifest =
          new byte[(int) filesystem.getFileSize(cachedBuildFileManifestPath)];
      fis.read(deserializedBuildFileManifest);
    } catch (IOException t) {
      throw new ParserCacheException(
          t,
          "Failed to deserialize weak fingerprint file for BuildFileManifgest from file %s.",
          weakFingerprintCachePath);
    }
    return deserializedBuildFileManifest;
  }

  private Path getWeakFingerprintPath(HashCode weakFingerprint) throws ParserCacheException {
    Path weakFingerprintCachePath = localCachePath.resolve(weakFingerprint.toString());

    if (!filesystem.exists(weakFingerprintCachePath)) {
      throw new ParserCacheException(
          "Cannot find weakFingerprint directory: %s.", weakFingerprintCachePath);
    }
    return weakFingerprintCachePath;
  }
}
