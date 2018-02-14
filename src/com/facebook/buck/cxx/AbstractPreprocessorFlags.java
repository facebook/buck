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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractPreprocessorFlags implements AddsToRuleKey {

  /** File set via {@code -include}. */
  @AddToRuleKey
  @Value.Parameter
  public abstract Optional<SourcePath> getPrefixHeader();

  /** Other flags included as is. */
  @AddToRuleKey
  @Value.Parameter
  @Value.Default
  public CxxToolFlags getOtherFlags() {
    return CxxToolFlags.of();
  }

  /** Directories set via {@code -I}. */
  @AddToRuleKey
  @Value.Parameter
  public abstract ImmutableSet<CxxHeaders> getIncludes();

  /** Directories set via {@code -F}. */
  @AddToRuleKey
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getFrameworkPaths();

  @Value.Lazy
  public CxxIncludePaths getCxxIncludePaths() {
    return CxxIncludePaths.of(getIncludes(), getFrameworkPaths());
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(
        Optionals.toStream(getPrefixHeader())
            .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
            .iterator());
    for (CxxHeaders cxxHeaders : getIncludes()) {
      cxxHeaders.getDeps(ruleFinder).forEachOrdered(deps::add);
    }
    for (FrameworkPath frameworkPath : getFrameworkPaths()) {
      deps.addAll(frameworkPath.getDeps(ruleFinder));
    }
    for (Arg arg : getOtherFlags().getAllFlags()) {
      deps.addAll(BuildableSupport.getDepsCollection(arg, ruleFinder));
    }
    return deps.build();
  }

  public CxxToolFlags getIncludePathFlags(
      SourcePathResolver resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor) {
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(
            StringArg.from(
                getCxxIncludePaths()
                    .getFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor)))
        .build();
  }

  public CxxToolFlags getNonIncludePathFlags(
      SourcePathResolver resolver, Optional<CxxPrecompiledHeader> pch, Preprocessor preprocessor) {
    ExplicitCxxToolFlags.Builder builder = CxxToolFlags.explicitBuilder();
    ExplicitCxxToolFlags.addCxxToolFlags(builder, getOtherFlags());
    if (pch.isPresent()) {
      boolean precompiled = pch.get().canPrecompile();
      builder.addAllRuleFlags(
          StringArg.from(
              preprocessor.prefixOrPCHArgs(precompiled, pch.get().getIncludeFilePath(resolver))));
    }
    return builder.build();
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolver resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor,
      Optional<CxxPrecompiledHeader> pch) {
    return CxxToolFlags.concat(
        getNonIncludePathFlags(resolver, pch, preprocessor),
        getIncludePathFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor));
  }
}
