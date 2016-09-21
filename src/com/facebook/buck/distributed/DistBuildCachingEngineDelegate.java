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

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link CachingBuildEngineDelegate} for use when building from a state file
 * in distributed build.
 */
public class DistBuildCachingEngineDelegate implements CachingBuildEngineDelegate {
  private final LoadingCache<ProjectFilesystem, FileHashCache> fileHashCacheLoader;
  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyBuilderFactory> ruleKeyFactories;

  public DistBuildCachingEngineDelegate(
      SourcePathResolver sourcePathResolver,
      final DistBuildState remoteState,
      final FileContentsProvider provider) {
    this.fileHashCacheLoader = CacheBuilder.newBuilder()
        .build(new CacheLoader<ProjectFilesystem, FileHashCache>() {
          @Override
          public FileHashCache load(@Nonnull ProjectFilesystem filesystem) {
            FileHashCache remoteCache = remoteState.createFileHashLoader(filesystem);
            FileHashCache cellCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
            FileHashCache buckOutCache = DefaultFileHashCache.createBuckOutFileHashCache(
                new ProjectFilesystem(
                    filesystem.getRootPath(),
                    ImmutableSet.<PathOrGlobMatcher>of()),
                filesystem.getBuckPaths().getBuckOut());
            return new StackedFileHashCache(
                ImmutableList.of(remoteCache, cellCache, buckOutCache));
          }
        });
    ruleKeyFactories = DistBuildFileHashes.createRuleKeyFactories(
        sourcePathResolver,
        CacheBuilder.newBuilder().build(new CacheLoader<ProjectFilesystem, FileHashLoader>() {
          @Override
          public FileHashLoader load(ProjectFilesystem filesystem) throws Exception {
            return remoteState.createMaterializingLoader(filesystem, provider);
          }
        }),
        /* keySeed */ 0);
  }

  @Override
  public LoadingCache<ProjectFilesystem, FileHashCache> createFileHashCacheLoader() {
    return fileHashCacheLoader;
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
