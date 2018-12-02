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
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * A hybrid implementation for the {@link ParserCacheStorage} interface. A hybrid storage multiple
 * storages and allow reading from and writing to them, based on the access mode specified in the
 * options for the specific storage. )
 */
public class HybridCacheStorage implements ParserCacheStorage {
  private static final Logger LOG = Logger.get(HybridCacheStorage.class);

  // TODO(buck_team): Make this a list of ParserCacheStorage instead of two specific ones.
  @VisibleForTesting final ParserCacheStorage localCacheStorage;
  @VisibleForTesting final ParserCacheStorage remoteCacheStorage;

  private HybridCacheStorage(
      ParserCacheStorage localCacheStorage, ParserCacheStorage remoteCacheStorage) {
    this.localCacheStorage = localCacheStorage;
    this.remoteCacheStorage = remoteCacheStorage;
  }

  /**
   * Static factory for creating {@link HybridCacheStorage} objects.
   *
   * @param localCacheStorage the {@link LocalCacheStorage} object to be used for this parsing.
   * @param remoteCacheStorage the {@link RemoteManifestServiceCacheStorage} object to be used for
   *     this parsing.
   * @return a new instance of fully instantiated hybrid cache object.
   */
  public static ParserCacheStorage of(
      ParserCacheStorage localCacheStorage, ParserCacheStorage remoteCacheStorage) {
    return new HybridCacheStorage(localCacheStorage, remoteCacheStorage);
  }

  @Override
  public void storeBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint, byte[] serializedBuildFileManifest)
      throws IOException, InterruptedException {
    Stopwatch timer = null;
    if (LOG.isLoggable(Level.FINE)) {
      timer = Stopwatch.createStarted();
    }

    // TODO(buck_team): Both stores can be done in parallel. The remote store is already done in
    // parallel
    // in the client, so the remote store is immediate return.
    // The local store is very cheap operation and we don't expect to do this often - store will
    // happen only once and the reads will be dominant.
    // Measure the effect of the store and optimize if needed.
    try {
      IOException firstException = null;
      try {
        localCacheStorage.storeBuildFileManifest(
            weakFingerprint, strongFingerprint, serializedBuildFileManifest);
      } catch (IOException e) {
        firstException = e;
      }

      IOException secondException = null;
      try {
        remoteCacheStorage.storeBuildFileManifest(
            weakFingerprint, strongFingerprint, serializedBuildFileManifest);
      } catch (IOException e) {
        secondException = e;
      }

      if (firstException != null && secondException != null) {
        firstException.addSuppressed(secondException);
        throw firstException;
      }

      if (firstException != null) {
        throw firstException;
      }

      if (secondException != null) {
        throw secondException;
      }
    } finally {
      if (timer != null) {
        LOG.debug(
            "Time to complete HybridCacheStorage.storeBuildFileManifest method: %d ns.",
            timer.stop().elapsed(TimeUnit.NANOSECONDS));
      }
    }
  }

  /**
   * Gets the {@link BuildFileManifest} from some of the aggregated caches.
   *
   * @param weakFingerprint the weak fingerprint for the {@code buildFileManifest}.
   * @param strongFingerprint the strong fingerprint for the {@code buildFileManifest}.
   * @return a {@link BuildFileManifest} fom some of the aggregated caches, if one available.
   * @throws ParserCacheException
   *     <p>Note: This method stores values obtained from the remote cache into the local one.
   */
  @Override
  public Optional<BuildFileManifest> getBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException {
    Stopwatch timer = null;
    if (LOG.isLoggable(Level.FINE)) {
      timer = Stopwatch.createStarted();
    }

    try {
      Optional<BuildFileManifest> localBuildFileManifest =
          localCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
      if (localBuildFileManifest.isPresent()) {
        return localBuildFileManifest;
      }

      Optional<BuildFileManifest> remoteBuildFileManifest =
          remoteCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
      if (remoteBuildFileManifest.isPresent()) {
        // Store in local cache, so we don't ask again the remote.
        // TODO(buck_team): The serializeBuildFileManifestToBytes function returns a byte
        // representation
        // of the BuildFileManifest. It is possible to get this from the previous call to
        // getBuildFileManifest,
        // instead of recalculating it, but that would break the ParserStorage interface
        // encapsulation.
        // I expect this case to happen very rarely, since in most of the cases the cached results
        // will come from local cache.
        // If this becomes a problem we can optimize the interface and return the byte
        // representation of the manifest directly.
        // TODO(buck_team): This can be done on a separate thread. In general, the store operation
        // to
        // local cache is rather fast. If this becomes a problem, we should pul the storing on a
        // different thread.
        localCacheStorage.storeBuildFileManifest(
            weakFingerprint,
            strongFingerprint,
            BuildFileManifestSerializer.serialize(remoteBuildFileManifest.get()));
        return remoteBuildFileManifest;
      }

      return Optional.empty();
    } finally {
      if (timer != null) {
        LOG.debug(
            "Time to complete HybridCacheStorage.getBuildFileManifest method: %d ns.",
            timer.stop().elapsed(TimeUnit.NANOSECONDS));
      }
    }
  }

  /**
   * Deletes the records corresponding on the {@code weakFingerprint and {@code strongFingerprint}.
   * Note:
   *   Make sure we execute both deletes and if they both throw, the exception from the second
   *   delete is stored as suppressed exception on the first one. Otherwise, the appropriate exception is rethrown.
   * @param weakFingerprint the {@code weakFingerprint} for which to remove the associated cache
   *     records.
   * @param strongFingerprint the {@code strongFingerprint} for which to remove the associated cache
   * @throws ParserCacheException
   */
  @Override
  public void deleteCacheEntries(HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException {

    IOException firstException = null;
    try {
      localCacheStorage.deleteCacheEntries(weakFingerprint, strongFingerprint);
    } catch (IOException e) {
      firstException = e;
    }

    try {
      remoteCacheStorage.deleteCacheEntries(weakFingerprint, strongFingerprint);
    } catch (IOException e) {
      if (firstException != null) {
        firstException.addSuppressed(e);
        throw firstException;
      }

      throw e;
    }

    if (firstException != null) {
      throw firstException;
    }
  }
}
