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

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ForwardingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * This class uses the {@link ParserCache} for parsing build specs, delegating to normal parse when
 * necessary.
 */
public class CachingProjectBuildFileParserDecorator
    extends ForwardingProjectBuildFileParserDecorator {
  private final ParserCache parserCache;

  private CachingProjectBuildFileParserDecorator(
      ParserCache parserCache, ProjectBuildFileParser delegate) {
    super(delegate);
    this.parserCache = parserCache;
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
      ParserCache parserCache, ProjectBuildFileParser delegate) {
    return new CachingProjectBuildFileParserDecorator(parserCache, delegate);
  }

  /**
   * Store a parsed entry in the cache.
   *
   * @param buildFileManifest the {@code BuildFileManifest} to store.
   * @return the result of the store in cache operation.
   */
  private void storeManifestInCache(Path buildFile, BuildFileManifest buildFileManifest)
      throws InterruptedException {
    parserCache.storeBuildFileManifest(buildFile, buildFileManifest);
  }

  // calculate the globs state is proper for using the returned manifest from storage.
  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {

    Optional<BuildFileManifest> buildFileManifestFormCache =
        parserCache.getBuildFileManifest(buildFile, delegate);

    if (buildFileManifestFormCache.isPresent()) {
      return buildFileManifestFormCache.get();
    }

    BuildFileManifest parsedManifest = delegate.getBuildFileManifest(buildFile);

    storeManifestInCache(buildFile, parsedManifest);

    return parsedManifest;
  }
}
