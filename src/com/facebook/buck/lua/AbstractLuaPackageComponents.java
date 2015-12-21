/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.lua;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

/**
 * Components that contribute to a Lua package.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractLuaPackageComponents implements RuleKeyAppendable {

  /**
   * @return mapping of module names to their respective {@link SourcePath}s.
   */
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, SourcePath> getModules();

  /**
   * @return a mapping of shared native library names to their respective {@link SourcePath}s.
   */
  @Value.NaturalOrder
  public abstract ImmutableSortedMap<String, SourcePath> getNativeLibraries();

  public static LuaPackageComponents concat(Iterable<LuaPackageComponents> inputs) {
    LuaPackageComponents.Builder builder = LuaPackageComponents.builder();
    for (LuaPackageComponents input : inputs) {
      builder.putAllModules(input.getModules());
      builder.putAllNativeLibraries(input.getNativeLibraries());
    }
    return builder.build();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("modules", getModules());
    builder.setReflectively("nativeLibraries", getNativeLibraries());
    return builder;
  }

  public ImmutableSortedSet<BuildRule> getDeps(SourcePathResolver resolver) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resolver.filterBuildRuleInputs(getModules().values()))
        .addAll(resolver.filterBuildRuleInputs(getNativeLibraries().values()))
        .build();
  }

}
