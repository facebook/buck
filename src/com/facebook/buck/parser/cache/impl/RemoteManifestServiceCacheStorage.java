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

import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A remote, thrift backed implementation for the {@link ParserCacheStorage} interface. */
public class RemoteManifestServiceCacheStorage implements ParserCacheStorage {
  private static final Logger LOG = Logger.get(RemoteManifestServiceCacheStorage.class);
  private static final long TIMEOUT = 1000;

  private final ParserCacheAccessMode cacheAccessMode;
  private final ManifestService manifestService;

  private RemoteManifestServiceCacheStorage(
      ManifestService manifestService, AbstractParserCacheConfig parserConfig) {
    this.cacheAccessMode = parserConfig.getRemoteCacheAccessMode();
    this.manifestService = manifestService;
  }

  private boolean isReadAllowed() {
    return cacheAccessMode.isReadable();
  }

  private boolean isWriteAllowed() {
    return cacheAccessMode.isWritable();
  }

  /**
   * Static factory for creating {@code RemoteManifestServiceCacheStorage} objects.
   *
   * @param manifestService the {@link ManifestService} object.
   * @param parserConfig the {@link com.facebook.buck.parser.cache.impl.AbstractParserCacheConfig}
   *     object.
   * @return a new instance of fully instantiated remote cache object.
   */
  public static RemoteManifestServiceCacheStorage of(
      ManifestService manifestService, AbstractParserCacheConfig parserConfig) {
    Preconditions.checkState(parserConfig.isRemoteParserCacheEnabled());
    return new RemoteManifestServiceCacheStorage(manifestService, parserConfig);
  }

  @Override
  public void storeBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint, byte[] serializedBuildFileManifest)
      throws IOException, InterruptedException {
    Stopwatch timer = Stopwatch.createStarted();
    try {
      if (!isWriteAllowed()) {
        return;
      }

      Manifest weakFingerprintManifest = new Manifest();
      weakFingerprintManifest.setKey(weakFingerprint.toString());
      weakFingerprintManifest.addToValues(
          ByteBuffer.wrap(strongFingerprint.toString().getBytes(StandardCharsets.UTF_8)));

      try {
        manifestService
            .appendToManifest(weakFingerprintManifest)
            .get(TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException e) {
        rethrow(e);
      }

      Manifest strongFingerprintManifest = new Manifest();
      strongFingerprintManifest.setKey(strongFingerprint.toString());
      strongFingerprintManifest.setValues(
          ImmutableList.of(ByteBuffer.wrap(serializedBuildFileManifest)));
      try {
        manifestService.setManifest(strongFingerprintManifest).get(TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException e) {
        rethrow(e);
      }
    } finally {
      timer.stop();
      LOG.debug(
          "Time to complete RemoteManifestServiceCacheStorage.storeBuildFileManifest method: %d.",
          timer.elapsed(TimeUnit.NANOSECONDS));
    }
  }

  @Override
  public Optional<BuildFileManifest> getBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException {
    Stopwatch timer = Stopwatch.createStarted();
    try {
      if (!isReadAllowed()) {
        return Optional.empty();
      }

      Manifest weakFingerprintManifest = null;

      try {
        weakFingerprintManifest =
            manifestService
                .fetchManifest(weakFingerprint.toString())
                .get(TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException e) {
        rethrow(e);
      }

      for (ByteBuffer bytes : weakFingerprintManifest.getValues()) {
        String strongFingerprintFromRemoteCache = new String(bytes.array(), StandardCharsets.UTF_8);
        if (strongFingerprintFromRemoteCache.equals(strongFingerprint.toString())) {
          // Found the relevant entry. Get it.
          try {
            Manifest strongFingerprintManifest =
                manifestService
                    .fetchManifest(strongFingerprintFromRemoteCache)
                    .get(TIMEOUT, TimeUnit.MILLISECONDS);
            // If the store failed to add entries to the values list (it happened couple of times
            // while testing because of hitting a timeout),
            // do not try to extract bytes from it.
            if (strongFingerprintManifest.getValuesSize() > 0) {
              return Optional.of(
                  BuildFileManifestSerializer.deserialize(
                      strongFingerprintManifest.getValues().get(0).array()));
            }
          } catch (ExecutionException | TimeoutException e) {
            rethrow(e);
          }
        }
      }

      return Optional.empty();
    } finally {
      timer.stop();
      LOG.debug(
          "Time to complete RemoteManifestServiceCacheStorage.getBuildFileManifest method: %d.",
          timer.elapsed(TimeUnit.NANOSECONDS));
    }
  }

  /** Rethrows exception gotten from future as IOException */
  private void rethrow(Exception e) throws IOException {
    Throwable cause = e.getCause();
    cause = cause == null ? e : cause;
    // Actually, manifest service can only throw IOException, but future does not know
    if (cause instanceof IOException) {
      throw (IOException) cause;
    }
    throw new IOException(cause);
  }

  @Override
  public void deleteCacheEntries(HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException {
    if (!isWriteAllowed()) {
      return;
    }

    Exception firstException = null;
    try {
      manifestService.deleteManifest(weakFingerprint.toString()).get();
    } catch (ExecutionException e) {
      firstException = e;
    }

    Exception secondException = null;
    try {
      manifestService.deleteManifest(strongFingerprint.toString()).get();
    } catch (ExecutionException e) {
      secondException = e;
    }

    if (firstException != null && secondException != null) {
      IOException throwException =
          new IOException("Failed to delete cache entries from remote cache", secondException);
      throwException.addSuppressed(firstException);
      throw throwException;
    }

    if (firstException != null) {
      throw new IOException("Failed to delete cache entries from remote cache", firstException);
    } else if (secondException != null) {
      throw new IOException("Failed to delete cache entries from remote cache", secondException);
    }
  }
}
