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

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class PreprocessorFlags implements AddsToRuleKey {

  /** File set via {@code -include}. This might be a prefix header or a precompiled header. */
  @AddToRuleKey
  public abstract Optional<SourcePath> getPrefixHeader();

  /** Other flags included as is. */
  @AddToRuleKey
  @Value.Default
  public CxxToolFlags getOtherFlags() {
    return CxxToolFlags.of();
  }

  /** Directories set via {@code -I}. */
  @AddToRuleKey
  public abstract ImmutableList<CxxHeaders> getIncludes();

  /** Directories set via {@code -F}. */
  @AddToRuleKey
  public abstract ImmutableList<FrameworkPath> getFrameworkPaths();

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  @Value.Derived
  public CxxIncludePaths getCxxIncludePaths() {
    return ImmutableCxxIncludePaths.of(getIncludes(), getFrameworkPaths());
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(ruleFinder.filterBuildRuleInputs(getPrefixHeader()).iterator());
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
      SourcePathResolverAdapter resolver,
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
      SourcePathResolverAdapter resolver,
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
      SourcePathResolverAdapter resolver,
      Optional<Pair<PrecompiledHeaderData, PathShortener>> pchAndShortener,
      Preprocessor preprocessor) {
    ExplicitCxxToolFlags.Builder builder = CxxToolFlags.explicitBuilder();
    ExplicitCxxToolFlags.addCxxToolFlags(builder, getOtherFlags());
    if (pchAndShortener.isPresent()) {
      PrecompiledHeaderData pch = pchAndShortener.get().getFirst();
      PathShortener shortener = pchAndShortener.get().getSecond();
      builder.addAllRuleFlags(
          StringArg.from(
              preprocessor.prefixOrPCHArgs(
                  pch.isPrecompiled(),
                  shortener.shorten(resolver.getAbsolutePath(pch.getHeader())))));
    }
    return builder.build();
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolverAdapter resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor,
      Optional<PrecompiledHeaderData> precompiledHeader) {
    return CxxToolFlags.concat(
        getNonIncludePathFlags(
            resolver, precompiledHeader.map(pch -> new Pair<>(pch, pathShortener)), preprocessor),
        getIncludePathFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor));
  }

  public static PreprocessorFlags of(
      Optional<SourcePath> prefixHeader,
      CxxToolFlags otherFlags,
      ImmutableList<CxxHeaders> includes,
      ImmutableList<FrameworkPath> frameworkPaths) {
    return builder()
        .setPrefixHeader(prefixHeader)
        .setOtherFlags(otherFlags)
        .setIncludes(includes)
        .setFrameworkPaths(frameworkPaths)
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public PreprocessorFlags withFrameworkPaths(ImmutableList<FrameworkPath> frameworkPaths) {
    if (getFrameworkPaths().equals(frameworkPaths)) {
      return this;
    }
    return builder().from(this).setFrameworkPaths(frameworkPaths).build();
  }

  public PreprocessorFlags withOtherFlags(CxxToolFlags otherFlags) {
    if (getOtherFlags().equals(otherFlags)) {
      return this;
    }
    return builder().from(this).setOtherFlags(otherFlags).build();
  }

  public PreprocessorFlags withPrefixHeader(Optional<SourcePath> prefixHeader) {
    if (getPrefixHeader().equals(prefixHeader)) {
      return this;
    }
    return builder().from(this).setPrefixHeader(prefixHeader).build();
  }

  public static class Builder extends ImmutablePreprocessorFlags.Builder {}
}
