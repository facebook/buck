/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Custom cache to use with {@link CxxToolProvider} and similar classes that need to cache build
 * rules for a given build rule resolver and target configuration.
 *
 * <p>This is basically two level cache to allow to use build rule resolver as a key in a cache with
 * weak keys and target configuration as the key at second level (without weak keys).
 */
public class BuildRuleResolverCacheByTargetConfiguration<T> {
  private final LoadingCache<TargetConfiguration, T> cache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<TargetConfiguration, T>() {
                @Override
                public T load(@Nonnull TargetConfiguration targetConfiguration) {
                  return delegateBuilder.apply(
                      toolProvider.resolve(
                          Preconditions.checkNotNull(buildRuleResolver.get()),
                          targetConfiguration));
                }
              });

  // BuildRuleResolverCacheByTargetConfiguration instances are values in a cache keyed by a
  // weak reference to a BuildRuleResolver. We must also make this a WeakReference, otherwise
  // the GC will never free entries in the cache.
  private final WeakReference<BuildRuleResolver> buildRuleResolver;
  private final ToolProvider toolProvider;
  private final Function<Tool, T> delegateBuilder;

  public BuildRuleResolverCacheByTargetConfiguration(
      BuildRuleResolver buildRuleResolver,
      ToolProvider toolProvider,
      Function<Tool, T> delegateBuilder) {
    this.buildRuleResolver = new WeakReference<>(buildRuleResolver);
    this.toolProvider = toolProvider;
    this.delegateBuilder = delegateBuilder;
  }

  public T get(TargetConfiguration targetConfiguration) {
    return cache.getUnchecked(targetConfiguration);
  }
}
