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

import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SupportsColorsInOutput;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;

import java.util.List;

/**
 * Helper class for generating compiler invocations for a cxx compilation rule.
 */
class CompilerDelegate implements RuleKeyAppendable {
  // Fields that are added to rule key as is.
  private final Compiler compiler;

  // Fields that added to the rule key with some processing.
  private final ImmutableList<String> platformCompilerFlags;
  private final ImmutableList<String> ruleCompilerFlags;

  // Fields that are not added to the rule key.
  private final SourcePathResolver resolver;
  private final DebugPathSanitizer sanitizer;

  public CompilerDelegate(
      SourcePathResolver resolver,
      DebugPathSanitizer sanitizer,
      Compiler compiler,
      List<String> platformCompilerFlags,
      List<String> ruleCompilerFlags) {
    this.resolver = resolver;
    this.sanitizer = sanitizer;
    this.compiler = compiler;
    this.platformCompilerFlags = ImmutableList.copyOf(platformCompilerFlags);
    this.ruleCompilerFlags = ImmutableList.copyOf(ruleCompilerFlags);
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively("compiler", compiler);
    builder.setReflectively(
        "platformCompilerFlags",
        sanitizer.sanitizeFlags(platformCompilerFlags));
    builder.setReflectively(
        "ruleCompilerFlags",
        sanitizer.sanitizeFlags(ruleCompilerFlags));
    return builder;
  }

  /**
   * Returns the argument list for executing the compiler.
   *
   * @param preprocessorDelegate If present, generate an argument list that operates on original
   *                             (un-preprocessed) inputs.
   */
  public ImmutableList<String> getCommand(Optional<PreprocessorDelegate> preprocessorDelegate) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(compiler.getCommandPrefix(resolver));
    if (preprocessorDelegate.isPresent()) {
      builder.addAll(preprocessorDelegate.get().getPreprocessorPlatformPrefix());
    }
    builder.addAll(platformCompilerFlags);
    if (preprocessorDelegate.isPresent()) {
      builder.addAll(preprocessorDelegate.get().getPreprocessorSuffix());
    }
    builder.addAll(ruleCompilerFlags);
    builder.addAll(
        compiler.debugCompilationDirFlags(sanitizer.getCompilationDirectory())
            .or(ImmutableList.<String>of()));
    return builder.build();
  }

  public ImmutableList<String> getPlatformCompilerFlags() {
    return platformCompilerFlags;
  }

  public ImmutableList<String> getRuleCompilerFlags() {
    return ruleCompilerFlags;
  }

  public ImmutableMap<String, String> getEnvironment() {
    return compiler.getEnvironment(resolver);
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
    return Ordering.natural().immutableSortedCopy(compiler.getInputs());
  }

  public Optional<SupportsColorsInOutput> getColorSupport() {
    return Optionals.cast(compiler, SupportsColorsInOutput.class);
  }

}
