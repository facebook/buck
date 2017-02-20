/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.function.Function;

import javax.annotation.Nonnull;

/**
 * A class which generates {@link RuleKeyFactories} objects.
 */
public class RuleKeyFactoryManager {

  private final int keySeed;
  private final Function<ProjectFilesystem, FileHashCache> fileHashCacheProvider;
  private final BuildRuleResolver resolver;
  private final long inputRuleKeyFileSizeLimit;

  private final LoadingCache<ProjectFilesystem, RuleKeyFactories> cache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<ProjectFilesystem, RuleKeyFactories>() {
                @Override
                public RuleKeyFactories load(@Nonnull ProjectFilesystem filesystem) {
                  return create(filesystem);
                }
              });

  public RuleKeyFactoryManager(
      int keySeed,
      Function<ProjectFilesystem, FileHashCache> fileHashCacheProvider,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit) {
    this.keySeed = keySeed;
    this.fileHashCacheProvider = fileHashCacheProvider;
    this.resolver = resolver;
    this.inputRuleKeyFileSizeLimit = inputRuleKeyFileSizeLimit;
  }

  private RuleKeyFactories create(ProjectFilesystem filesystem) {
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(keySeed);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache fileHashCache = fileHashCacheProvider.apply(filesystem);
    return RuleKeyFactories.of(
        new DefaultRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder),
        new InputBasedRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            inputRuleKeyFileSizeLimit),
        new DefaultDependencyFileRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder));
  }

  public Function<ProjectFilesystem, RuleKeyFactories> getProvider() {
    return cache::getUnchecked;
  }

}
