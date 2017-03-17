/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.cache.LoadingCache;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * Implementation of {@link CachingBuildEngineDelegate} for use when building from a state file
 * in distributed build.
 */
public class DistBuildCachingEngineDelegate implements CachingBuildEngineDelegate {


  private final StackedFileHashCache remoteStackedFileHashCache;
  private final StackedFileHashCache materializingStackedFileHashCache;

  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> ruleKeyFactories;

  /**
   *
   * @param sourcePathResolver Distributed build source parse resolver.
   * @param ruleFinder Used by the distributed build rule key factories.
   * @param remoteState The distributed build remote state.
   * @param fileHashCacheStack Can compute file hashes for all cells in the dist build.
   * @param provider Fetches the contents of files on demand.
   */
  public DistBuildCachingEngineDelegate(
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      DistBuildState remoteState,
      StackedFileHashCache fileHashCacheStack,
      FileContentsProvider provider) {

    // Used for rule key computations.
    this.remoteStackedFileHashCache = fileHashCacheStack.newDecoratedFileHashCache(
        cache -> remoteState.createRemoteFileHashCache(cache));

    // Used for the real build.
    this.materializingStackedFileHashCache = remoteStackedFileHashCache.newDecoratedFileHashCache(
        cache -> {
          try {
            return remoteState.createMaterializer(cache, provider);
          } catch (IOException exception) {
            throw new RuntimeException(
                String.format(
                    "Failed to create the Materializer for file system [%s]",
                    cache.getFilesystem()),
                exception);
          }
        });
    ruleKeyFactories = DistBuildFileHashes.createRuleKeyFactories(
        sourcePathResolver,
        ruleFinder,
        materializingStackedFileHashCache,
        /* keySeed */ 0);
  }

  @Override
  public FileHashCache getFileHashCache() {
    return remoteStackedFileHashCache;
  }

  @Override
  public void onRuleAboutToBeBuilt(BuildRule buildRule) {
    try {
      ruleKeyFactories.get(buildRule.getProjectFilesystem()).build(buildRule);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
