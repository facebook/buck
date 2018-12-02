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
import com.facebook.buck.parser.api.ForwardingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.config.Config;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * This class uses the {@link ParserCache} for parsing build specs, delegating to normal parse when
 * necessary.
 */
public class CachingProjectBuildFileParserDecorator
    extends ForwardingProjectBuildFileParserDecorator {
  private static final Logger LOG = Logger.get(CachingProjectBuildFileParserDecorator.class);

  private final ParserCache parserCache;
  private final Config config;
  private final ProjectFilesystem filesystem;
  private final FileHashCache fileHashCache;

  private CachingProjectBuildFileParserDecorator(
      ParserCache parserCache,
      ProjectBuildFileParser delegate,
      Config config,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache) {
    super(delegate);
    this.parserCache = parserCache;
    this.config = config;
    this.filesystem = filesystem;
    this.fileHashCache = fileHashCache;
  }

  /**
   * Creates a {@link CachingProjectBuildFileParserDecorator} object.
   *
   * @param parserCache the {@link ParserCache} implementation.
   * @param delegate a delegate parser that would be invoked to parse the file if the result of this
   *     parse is not cached.
   * @return a new instance of a caching parser.
   */
  public static CachingProjectBuildFileParserDecorator of(
      ParserCache parserCache,
      ProjectBuildFileParser delegate,
      Config config,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache) {
    return new CachingProjectBuildFileParserDecorator(
        parserCache, delegate, config, filesystem, fileHashCache);
  }

  // calculate the globs state is proper for using the returned manifest from storage.
  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {

    @Nullable HashCode weakFingerprint = null;
    @Nullable HashCode strongFingerprint = null;

    Optional<BuildFileManifest> buildFileManifestFromCache = Optional.empty();

    try {
      ImmutableSortedSet<String> includeBuildFiles = delegate.getIncludedFiles(buildFile);
      weakFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
      strongFingerprint =
          Fingerprinter.getStrongFingerprint(filesystem, includeBuildFiles, fileHashCache);

      buildFileManifestFromCache =
          parserCache.getBuildFileManifest(buildFile, delegate, weakFingerprint, strongFingerprint);

      if (buildFileManifestFromCache.isPresent()) {
        return buildFileManifestFromCache.get();
      }
    } catch (IOException e) {
      LOG.error(e, "Failure getting BuildFileManifest in caching decorator.");
    } catch (BuildFileParseException e) {
      LOG.debug(
          e, "Failure getting includes when getting the BuildFileManifest in caching decorator.");
    }

    BuildFileManifest parsedManifest = delegate.getBuildFileManifest(buildFile);

    if (weakFingerprint != null && strongFingerprint != null) {
      try {
        parserCache.storeBuildFileManifest(
            buildFile, parsedManifest, weakFingerprint, strongFingerprint);
      } catch (IOException e) {
        LOG.error(e, "Failure storing parsed BuildFileManifest.");
      }
    }

    return parsedManifest;
  }
}
