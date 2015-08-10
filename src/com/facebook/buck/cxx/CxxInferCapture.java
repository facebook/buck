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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * Generate the CFG for a source file
 */
public class CxxInferCapture extends AbstractBuildRule implements RuleKeyAppendable {

  @AddToRuleKey
  private final CxxInferTools inferTools;
  private final Optional<ImmutableList<String>> platformPreprocessorFlags;
  private final Optional<ImmutableList<String>> rulePreprocessorFlags;
  private final Optional<ImmutableList<String>> platformCompilerFlags;
  private final Optional<ImmutableList<String>> ruleCompilerFlags;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  @AddToRuleKey(stringify = true)
  private final Path output;
  private final ImmutableSet<Path> includeRoots;
  private final ImmutableSet<Path> systemIncludeRoots;
  private final ImmutableSet<Path> headerMaps;
  private final ImmutableSet<Path> frameworkRoots;
  @AddToRuleKey
  private final ImmutableList<CxxHeaders> includes;

  private final Path resultsDir;
  private final DebugPathSanitizer sanitizer;

  CxxInferCapture(
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      Optional<ImmutableList<String>> platformPreprocessorFlags,
      Optional<ImmutableList<String>> rulePreprocessorFlags,
      Optional<ImmutableList<String>> platformCompilerFlags,
      Optional<ImmutableList<String>> ruleCompilerFlags,
      SourcePath input,
      AbstractCxxSource.Type inputType,
      Path output,
      ImmutableSet<Path> includeRoots,
      ImmutableSet<Path> systemIncludeRoots,
      ImmutableSet<Path> headerMaps,
      ImmutableSet<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      CxxInferTools inferTools,
      DebugPathSanitizer sanitizer) {
    super(buildRuleParams, pathResolver);
    this.platformPreprocessorFlags = platformPreprocessorFlags;
    this.rulePreprocessorFlags = rulePreprocessorFlags;
    this.platformCompilerFlags = platformCompilerFlags;
    this.ruleCompilerFlags = ruleCompilerFlags;
    this.input = input;
    this.inputType = inputType;
    this.output = output;
    this.includeRoots = includeRoots;
    this.systemIncludeRoots = systemIncludeRoots;
    this.headerMaps = headerMaps;
    this.frameworkRoots = frameworkRoots;
    this.includes = includes;
    this.inferTools = inferTools;
    this.resultsDir = BuildTargets.getGenPath(this.getBuildTarget(), "infer-out-%s");
    this.sanitizer = sanitizer;
  }

  private ImmutableList<String> getPreprocessorPlatformPrefix() {
    return platformPreprocessorFlags.get();
  }

  private ImmutableList<String> getCompilerPlatformPrefix() {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(getPreprocessorPlatformPrefix());
    flags.addAll(platformCompilerFlags.get());
    return flags.build();
  }

  private ImmutableList<String> getCompilerSuffix() {
    ImmutableList.Builder<String> suffix = ImmutableList.builder();
    suffix.addAll(getPreprocessorSuffix());
    suffix.addAll(ruleCompilerFlags.get());
    return suffix.build();
  }

  private ImmutableList<String> getPreprocessorSuffix() {
    ImmutableSet.Builder<SourcePath> prefixHeaders = ImmutableSet.builder();
    for (CxxHeaders cxxHeaders : includes) {
      prefixHeaders.addAll(cxxHeaders.getPrefixHeaders());
    }
    return ImmutableList.<String>builder()
        .addAll(rulePreprocessorFlags.get())
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-include"),
                FluentIterable.from(prefixHeaders.build())
                    .transform(getResolver().getPathFunction())
                    .transform(Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(headerMaps, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludeRoots, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-F"),
                Iterables.transform(frameworkRoots, Functions.toStringFunction())))
        .build();
  }

  private ImmutableList<String> getFrontendCommand() {
    // TODO(martinoluca): Add support for extra arguments (and add them to the rulekey)
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    return commandBuilder
        .addAll(this.inferTools.topLevel.getCommandPrefix(getResolver()))
        .add("-a", "capture")
        .add("--project_root", getProjectFilesystem().getRootPath().toString())
        .add("--out", resultsDir.toString())
        .add("--")
        .add("clang")
        .addAll(getCompilerPlatformPrefix())
        .addAll(getCompilerSuffix())
        .add("-x", inputType.getLanguage())
        .add("-o", output.toString()) // TODO(martinoluca): Use -fsyntax-only for better perf
        .add("-c")
        .add(getResolver().getPath(input).toString())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<String> frontendCommand = getFrontendCommand();
    buildableContext.recordArtifact(this.getPathToOutput());

    return ImmutableList.<Step>builder()
        .add(new MkdirStep(resultsDir))
        .add(new MkdirStep(output.getParent()))
        .add(new DefaultShellStep(frontendCommand))
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return this.resultsDir;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    builder.setReflectively(
        "platformPreprocessorFlags",
        sanitizer.sanitizeFlags(platformPreprocessorFlags));
    builder.setReflectively(
        "rulePreprocessorFlags",
        sanitizer.sanitizeFlags(rulePreprocessorFlags));
    builder.setReflectively(
        "platformCompilerFlags",
        sanitizer.sanitizeFlags(platformCompilerFlags));
    builder.setReflectively(
        "ruleCompilerFlags",
        sanitizer.sanitizeFlags(ruleCompilerFlags));

    ImmutableList<String> frameworkRoots = FluentIterable.from(this.frameworkRoots)
        .transform(Functions.toStringFunction())
        .transform(sanitizer.sanitize(Optional.<Path>absent()))
        .toList();
    builder.setReflectively("frameworkRoots", frameworkRoots);

    return builder;
  }
}
