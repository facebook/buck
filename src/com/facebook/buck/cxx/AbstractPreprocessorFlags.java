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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractPreprocessorFlags implements AddsToRuleKey {

  /** File set via {@code -include}. This might be a prefix header or a precompiled header. */
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
  public abstract ImmutableList<CxxHeaders> getIncludes();

  /** Directories set via {@code -F}. */
  @AddToRuleKey
  @Value.Parameter
  public abstract ImmutableList<FrameworkPath> getFrameworkPaths();

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  @Value.Derived
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

  public CxxToolFlags getSanitizedIncludePathFlags(
      DebugPathSanitizer sanitizer,
      SourcePathResolver resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor) {
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(
            StringArg.from(
                sanitizer.sanitizeFlags(
                    getCxxIncludePaths()
                        .getFlags(
                            resolver, pathShortener, frameworkPathTransformer, preprocessor))))
        .build();
  }

  public CxxToolFlags getNonIncludePathFlags(
      SourcePathResolver resolver, Optional<PrecompiledHeaderData> pch, Preprocessor preprocessor) {
    ExplicitCxxToolFlags.Builder builder = CxxToolFlags.explicitBuilder();
    ExplicitCxxToolFlags.addCxxToolFlags(builder, getOtherFlags());
    if (pch.isPresent()) {
      builder.addAllRuleFlags(
          StringArg.from(
              preprocessor.prefixOrPCHArgs(
                  pch.get().isPrecompiled(), resolver.getAbsolutePath(pch.get().getHeader()))));
    }
    return builder.build();
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolver resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor,
      Optional<PrecompiledHeaderData> precompiledHeader) {
    return CxxToolFlags.concat(
        getNonIncludePathFlags(resolver, precompiledHeader, preprocessor),
        getIncludePathFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor));
  }
}
