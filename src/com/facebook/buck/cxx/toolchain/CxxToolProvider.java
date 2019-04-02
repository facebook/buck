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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Parameter;

/** A provide for the {@link Preprocessor} and {@link Compiler} C/C++ drivers. */
public abstract class CxxToolProvider<T> {

  @Immutable(builder = false, copy = false, prehash = true)
  interface CxxToolProviderCacheKey {
    @Parameter
    BuildRuleResolver getBuildRuleResolver();

    @Parameter
    TargetConfiguration getTargetConfiguration();
  }

  private final ToolProvider toolProvider;
  private final Supplier<Type> type;
  private final ToolType toolType;
  private final boolean useUnixFileSeparator;

  private final LoadingCache<CxxToolProviderCacheKey, T> cache =
      CacheBuilder.newBuilder()
          .weakKeys()
          .build(
              new CacheLoader<CxxToolProviderCacheKey, T>() {
                @Override
                public T load(@Nonnull CxxToolProviderCacheKey key) {
                  return build(
                      type.get(),
                      toolProvider.resolve(
                          key.getBuildRuleResolver(), key.getTargetConfiguration()));
                }
              });

  public CxxToolProvider(
      ToolProvider toolProvider,
      Supplier<Type> type,
      ToolType toolType,
      boolean useUnixFileSeparator) {
    this.toolProvider = toolProvider;
    this.type = type;
    this.toolType = toolType;
    this.useUnixFileSeparator = useUnixFileSeparator;
  }

  /**
   * Build using a {@link ToolProvider} and a required type. It also allows to specify to use Unix
   * path separators for the NDK compiler.
   */
  public CxxToolProvider(
      ToolProvider toolProvider, Type type, ToolType toolType, boolean useUnixFileSeparator) {
    this(toolProvider, Suppliers.ofInstance(type), toolType, useUnixFileSeparator);
  }

  /** Build using a {@link ToolProvider} and a required type. */
  public CxxToolProvider(ToolProvider toolProvider, Type type, ToolType toolType) {
    this(toolProvider, Suppliers.ofInstance(type), toolType, false);
  }

  protected abstract T build(Type type, Tool tool);

  public T resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return cache.getUnchecked(ImmutableCxxToolProviderCacheKey.of(resolver, targetConfiguration));
  }

  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return toolProvider.getParseTimeDeps(targetConfiguration);
  }

  public enum Type {
    CLANG,
    CLANG_CL,
    CLANG_WINDOWS,
    GCC,
    WINDOWS,
    WINDOWS_ML64
  }

  /** Return tool type of this provider instance */
  public ToolType getToolType() {
    return toolType;
  }

  /** @returns whether the specific CxxTool is accepting paths with Unix path separator only. */
  public boolean getUseUnixPathSeparator() {
    return useUnixFileSeparator;
  }
}
