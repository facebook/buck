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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** This class implements the caching behavior for parsing build specs. */
public class ParserCache {
  private final ParserCacheStorage parserCacheStorage;
  private final BuckEventBus eventBus;
  private final PerfEventId eventIdGet = PerfEventId.of("ParseFileCacheGet");
  private final PerfEventId eventIdStore = PerfEventId.of("ParseFileCacheStore");

  private ParserCache(ParserCacheStorage parserCacheStorage, BuckEventBus eventBus) {
    this.parserCacheStorage = parserCacheStorage;
    this.eventBus = eventBus;
  }

  /**
   * Creates a {@link ParserCache} object.
   *
   * @param filesystem the {link ProjectFilesystem} to use for locating the local cache.
   * @return a new instance of a caching parser.
   */
  public static ParserCache of(
      BuckConfig buckConfig,
      ProjectFilesystem filesystem,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      BuckEventBus eventBus) {
    return new ParserCache(
        ParserCacheStorageFactory.createParserCacheStorage(
            buckConfig, filesystem, manifestServiceSupplier),
        eventBus);
  }

  @VisibleForTesting
  ParserCacheStorage getParserCacheStorage() {
    return parserCacheStorage;
  }

  /**
   * Store a parsed entry in the cache, ignoring errors
   *
   * @param buildFile the BUCK file associated with the cell for which the parsing is performed
   * @param buildFileManifest the {@code BuildFileManifest} to store
   * @param weakFingerprint the weak fingerprint.
   * @param strongFingerprint the strong fingerprint.
   */
  public void storeBuildFileManifest(
      Path buildFile,
      BuildFileManifest buildFileManifest,
      HashCode weakFingerprint,
      HashCode strongFingerprint)
      throws InterruptedException, IOException {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, eventIdStore, "path", buildFile.toString())) {
      byte[] serializedManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
      parserCacheStorage.storeBuildFileManifest(
          weakFingerprint, strongFingerprint, serializedManifest);
    }
  }

  /**
   * Get build file manifest for the appropriate build file from cache, ignoring errors
   *
   * @param buildFile BUCK file to be parsed
   * @param parser Parser to retrieve meta information, like includes and globs, from build file
   * @param weakFingerprint the weak fingerprint.
   * @param strongFingerprint the strong fingerprint.
   * @return {@link BuildFileManifest} if found in cache, {@link Optional#empty()} if not found.
   */
  public Optional<BuildFileManifest> getBuildFileManifest(
      Path buildFile,
      ProjectBuildFileParser parser,
      HashCode weakFingerprint,
      HashCode strongFingerprint)
      throws IOException, InterruptedException, BuildFileParseException {

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus, eventIdGet, "path", buildFile.toString())) {
      // Try to retrieve a cached build file manifest
      Optional<BuildFileManifest> cachedManifest =
          parserCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
      if (cachedManifest.isPresent()
          && parser.globResultsMatchCurrentState(
              buildFile, cachedManifest.get().getGlobManifest())) {
        // There is a match only if the glob state on disk is the same as the recorded globs and
        // results.
        return cachedManifest;
      }

      return Optional.empty();
    }
  }
}
