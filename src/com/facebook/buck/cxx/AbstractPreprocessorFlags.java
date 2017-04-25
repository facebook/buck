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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractPreprocessorFlags {

  /** File set via {@code -include}. */
  @Value.Parameter
  public abstract Optional<SourcePath> getPrefixHeader();

  /** Other flags included as is. */
  @Value.Parameter
  @Value.Default
  public CxxToolFlags getOtherFlags() {
    return CxxToolFlags.of();
  }

  /** Directories set via {@code -I}. */
  @Value.Parameter
  public abstract ImmutableSet<CxxHeaders> getIncludes();

  /** Directories set via {@code -F}. */
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getFrameworkPaths();

  @Value.Lazy
  public CxxIncludePaths getCxxIncludePaths() {
    return CxxIncludePaths.of(getIncludes(), getFrameworkPaths());
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(ruleFinder.filterBuildRuleInputs(OptionalCompat.asSet(getPrefixHeader())));
    for (CxxHeaders cxxHeaders : getIncludes()) {
      deps.addAll(cxxHeaders.getDeps(ruleFinder));
    }
    for (FrameworkPath frameworkPath : getFrameworkPaths()) {
      deps.addAll(frameworkPath.getDeps(ruleFinder));
    }
    return deps.build();
  }

  /** Append to rule key the members which are not handled elsewhere. */
  public void appendToRuleKey(RuleKeyObjectSink sink, DebugPathSanitizer sanitizer) {
    sink.setReflectively("prefixHeader", getPrefixHeader());
    sink.setReflectively("includes", getIncludes());
    sink.setReflectively("frameworkRoots", getFrameworkPaths());

    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    sink.setReflectively(
        "platformPreprocessorFlags", sanitizer.sanitizeFlags(getOtherFlags().getPlatformFlags()));
    sink.setReflectively(
        "rulePreprocessorFlags", sanitizer.sanitizeFlags(getOtherFlags().getRuleFlags()));
  }

  public CxxToolFlags getIncludePathFlags(
      SourcePathResolver resolver,
      PathShortener pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer,
      Preprocessor preprocessor) {
    return CxxToolFlags.explicitBuilder()
        .addAllRuleFlags(
            getCxxIncludePaths()
                .getFlags(resolver, pathShortener, frameworkPathTransformer, preprocessor))
        .build();
  }

  public CxxToolFlags getNonIncludePathFlags(
      SourcePathResolver resolver, Optional<CxxPrecompiledHeader> pch, Preprocessor preprocessor) {
    ExplicitCxxToolFlags.Builder builder = CxxToolFlags.explicitBuilder();
    ExplicitCxxToolFlags.addCxxToolFlags(builder, getOtherFlags());
    builder.addAllRuleFlags(
        preprocessor.prefixOrPCHArgs(
            resolver,
            getPrefixHeader(),
            pch.map(CxxPrecompiledHeader::getSourcePathToOutput).map(resolver::getRelativePath)));
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
