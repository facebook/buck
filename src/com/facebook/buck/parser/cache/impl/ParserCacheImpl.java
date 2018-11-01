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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.cache.ParserCache;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.util.config.Config;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** This class implements the parser cache behavior for parsing build specs. */
public class ParserCacheImpl implements ParserCache {
  private static final Logger LOG = Logger.get(ParserCacheImpl.class);

  private final ParserCacheStorage localCacheStorage;
  private final ProjectFilesystem filesystem;
  private final Config config;
  private final AbstractParserCacheConfig parserCacheConfig;

  private AbstractParserCacheConfig obtainParserCacheConfig(BuckConfig buckConfig) {
    return buckConfig.getView(ParserCacheConfig.class);
  }

  private ParserCacheStorage createLocalParserStorage() {
    Preconditions.checkState(parserCacheConfig.isDirParserCacheEnabled());
    return LocalCacheStorage.newInstance(parserCacheConfig, filesystem);
  }

  private ParserCacheImpl(BuckConfig buckConfig, ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    this.config = buckConfig.getConfig();
    this.parserCacheConfig = obtainParserCacheConfig(buckConfig);
    this.localCacheStorage = createLocalParserStorage();
  }

  /**
   * Creates a {@link ParserCacheImpl} object.
   *
   * @param filesystem the {link ProjectFilesystem} to use for locating the local cache.
   * @return a new instance of a caching parser.
   */
  public static ParserCache of(BuckConfig buckConfig, ProjectFilesystem filesystem) {
    return new ParserCacheImpl(buckConfig, filesystem);
  }

  /**
   * This method looks for the cached result for this build spec and returns a constructed {@link
   * BuildFileManifest}.
   *
   * @return a constructed result object based on the cache found data or {@code Optional.empty()}
   *     if no cache info is found.
   */
  private Optional<BuildFileManifest> getManifestFromLocalCache(
      Path buildFile, ImmutableList<String> includeBuildFiles) throws IOException {
    final HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
    final HashCode strongFingerprint =
        Fingerprinter.getStrongFingerprint(filesystem, includeBuildFiles);
    try {
      return localCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
    } catch (ParserCacheException t) {
      LOG.error(t, "Exception getting BuildFileManifest from cache.");
    }

    return Optional.empty();
  }

  /**
   * Store a parsed entry in the cache.
   *
   * @param buildFileManifest the {@code BuildFileManifest} to store.
   */
  @Override
  public void storeBuildFileManifest(Path buildFile, BuildFileManifest buildFileManifest) {
    final HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
    final HashCode strongFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
    try {
      localCacheStorage.storeBuildFileManifest(
          weakFingerprint, strongFingerprint, buildFileManifest);
    } catch (ParserCacheException t) {
      LOG.error(t, "Exception while storing parsed BuildFileAccessManifest.");
    }
  }

  @Override
  public Optional<BuildFileManifest> getBuildFileManifest(
      Path buildFile, ProjectBuildFileParser parser) {

    // Get from local cache.
    try {
      ImmutableList<String> includeFilesForBuildFile = parser.getIncludedFiles(buildFile);
      Optional<BuildFileManifest> cachedManifest =
          getManifestFromLocalCache(buildFile, includeFilesForBuildFile);
      if (cachedManifest.isPresent()
          && parser.globResultsMatchCurrentState(
              buildFile, cachedManifest.get().getGlobManifest())) {
        // There is a match only if the glob state on disk is the same as the recorded globs and
        // results.
        return cachedManifest;
      }
    } catch (IOException | InterruptedException e) {
      LOG.error(e, "Exception while getting BuildFileAccessManifest from cache storage.");
    }

    return Optional.empty();
  }
}
