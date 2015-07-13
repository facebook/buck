/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Map;

/**
 * A build rule which preprocesses and/or compiles a C/C++ source in a single step.
 */
public class CxxPreprocessAndCompile
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsInputBasedRuleKey {

  @AddToRuleKey
  private final CxxPreprocessAndCompileStep.Operation operation;
  @AddToRuleKey
  private final Optional<Tool> preprocessor;
  private final Optional<ImmutableList<String>> platformPreprocessorFlags;
  private final Optional<ImmutableList<String>> rulePreprocessorFlags;
  @AddToRuleKey
  private final Optional<Compiler> compiler;
  private final Optional<ImmutableList<String>> platformCompilerFlags;
  private final Optional<ImmutableList<String>> ruleCompilerFlags;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  private final ImmutableSet<Path> includeRoots;
  private final ImmutableSet<Path> systemIncludeRoots;
  private final ImmutableSet<Path> frameworkRoots;
  @AddToRuleKey
  private final ImmutableList<CxxHeaders> includes;
  private final DebugPathSanitizer sanitizer;

  @VisibleForTesting
  CxxPreprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxPreprocessAndCompileStep.Operation operation,
      Optional<Tool> preprocessor,
      Optional<ImmutableList<String>> platformPreprocessorFlags,
      Optional<ImmutableList<String>> rulePreprocessorFlags,
      Optional<Compiler> compiler,
      Optional<ImmutableList<String>> platformCompilerFlags,
      Optional<ImmutableList<String>> ruleCompilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableSet<Path> includeRoots,
      ImmutableSet<Path> systemIncludeRoots,
      ImmutableSet<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    Preconditions.checkState(operation.isPreprocess() == preprocessor.isPresent());
    Preconditions.checkState(operation.isPreprocess() == platformPreprocessorFlags.isPresent());
    Preconditions.checkState(operation.isPreprocess() == rulePreprocessorFlags.isPresent());
    Preconditions.checkState(operation.isCompile() == compiler.isPresent());
    Preconditions.checkState(operation.isCompile() == platformCompilerFlags.isPresent());
    Preconditions.checkState(operation.isCompile() == ruleCompilerFlags.isPresent());
    this.operation = operation;
    this.preprocessor = preprocessor;
    this.platformPreprocessorFlags = platformPreprocessorFlags;
    this.rulePreprocessorFlags = rulePreprocessorFlags;
    this.compiler = compiler;
    this.platformCompilerFlags = platformCompilerFlags;
    this.ruleCompilerFlags = ruleCompilerFlags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.includeRoots = includeRoots;
    this.systemIncludeRoots = systemIncludeRoots;
    this.frameworkRoots = frameworkRoots;
    this.includes = includes;
    this.sanitizer = sanitizer;
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source.
   */
  public static CxxPreprocessAndCompile compile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Compiler compiler,
      ImmutableList<String> platformFlags,
      ImmutableList<String> ruleFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        Optional.<Tool>absent(),
        Optional.<ImmutableList<String>>absent(),
        Optional.<ImmutableList<String>>absent(),
        Optional.of(compiler),
        Optional.of(platformFlags),
        Optional.of(ruleFlags),
        output,
        input,
        inputType,
        ImmutableSet.<Path>of(),
        ImmutableSet.<Path>of(),
        ImmutableSet.<Path>of(),
        ImmutableList.<CxxHeaders>of(),
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses the given source.
   */
  public static CxxPreprocessAndCompile preprocess(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool preprocessor,
      ImmutableList<String> platformFlags,
      ImmutableList<String> ruleFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableSet<Path> includeRoots,
      ImmutableSet<Path> systemIncludeRoots,
      ImmutableSet<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        Optional.of(preprocessor),
        Optional.of(platformFlags),
        Optional.of(ruleFlags),
        Optional.<Compiler>absent(),
        Optional.<ImmutableList<String>>absent(),
        Optional.<ImmutableList<String>>absent(),
        output,
        input,
        inputType,
        includeRoots,
        systemIncludeRoots,
        frameworkRoots,
        includes,
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool preprocessor,
      ImmutableList<String> platformPreprocessorFlags,
      ImmutableList<String> rulePreprocessorFlags,
      Compiler compiler,
      ImmutableList<String> platformCompilerFlags,
      ImmutableList<String> ruleCompilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableSet<Path> includeRoots,
      ImmutableSet<Path> systemIncludeRoots,
      ImmutableSet<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      DebugPathSanitizer sanitizer,
      CxxPreprocessMode strategy) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        (strategy == CxxPreprocessMode.PIPED
            ? CxxPreprocessAndCompileStep.Operation.PIPED_PREPROCESS_AND_COMPILE
            : CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO),
        Optional.of(preprocessor),
        Optional.of(platformPreprocessorFlags),
        Optional.of(rulePreprocessorFlags),
        Optional.of(compiler),
        Optional.of(platformCompilerFlags),
        Optional.of(ruleCompilerFlags),
        output,
        input,
        inputType,
        includeRoots,
        systemIncludeRoots,
        frameworkRoots,
        includes,
        sanitizer);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    builder.setReflectively("platformPreprocessorFlags", sanitizeFlags(platformPreprocessorFlags));
    builder.setReflectively("rulePreprocessorFlags", sanitizeFlags(rulePreprocessorFlags));
    builder.setReflectively("platformCompilerFlags", sanitizeFlags(platformCompilerFlags));
    builder.setReflectively("ruleCompilerFlags", sanitizeFlags(ruleCompilerFlags));
    ImmutableList<String> frameworkRoots = FluentIterable.from(this.frameworkRoots)
        .transform(Functions.toStringFunction())
        .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
        .toList();
    builder.setReflectively("frameworkRoots", frameworkRoots);

    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      builder.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    }

    return builder;
  }

  private ImmutableList<String> sanitizeFlags(Optional<ImmutableList<String>> flags) {
    return FluentIterable.from(flags.or(ImmutableList.<String>of()))
        .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
        .toList();
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep() {

    // Resolve the map of symlinks to real paths to hand off the preprocess step.  If we're
    // compiling, this will just be empty.
    ImmutableMap.Builder<Path, Path> replacementPathsBuilder = ImmutableMap.builder();
    try {
      for (Map.Entry<Path, SourcePath> entry :
           CxxHeaders.concat(includes).getFullNameToPathMap().entrySet()) {
        replacementPathsBuilder.put(entry.getKey(), getResolver().getPath(entry.getValue()));
      }
    } catch (CxxHeaders.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }
    ImmutableMap<Path, Path> replacementPaths = replacementPathsBuilder.build();

    Optional<ImmutableList<String>> preprocessorCommand;
    if (preprocessor.isPresent()) {
      preprocessorCommand = Optional.of(
          ImmutableList.<String>builder()
              .addAll(preprocessor.get().getCommandPrefix(getResolver()))
              .addAll(getPreprocessorPlatformPrefix())
              .addAll(getPreprocessorSuffix())
              .build());
    } else {
      preprocessorCommand = Optional.absent();
    }

    Optional<ImmutableList<String>> compilerCommand;
    if (compiler.isPresent()) {
      compilerCommand = Optional.of(
          ImmutableList.<String>builder()
              .addAll(compiler.get().getCommandPrefix(getResolver()))
              .addAll(getCompilerPlatformPrefix())
              .addAll(getCompilerSuffix())
              .build());
    } else {
      compilerCommand = Optional.absent();
    }

    return new CxxPreprocessAndCompileStep(
        operation,
        output,
        getResolver().getPath(input),
        inputType,
        preprocessorCommand,
        compilerCommand,
        replacementPaths,
        sanitizer);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        makeMainStep());
  }

  private ImmutableList<String> getPreprocessorPlatformPrefix() {
    Preconditions.checkState(operation.isPreprocess());
    return platformPreprocessorFlags.get();
  }

  private ImmutableList<String> getCompilerPlatformPrefix() {
    Preconditions.checkState(operation.isCompile());
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      flags.addAll(getPreprocessorPlatformPrefix());
    }
    flags.addAll(platformCompilerFlags.get());
    return flags.build();
  }

  private ImmutableList<String> getPreprocessorSuffix() {
    Preconditions.checkState(operation.isPreprocess());
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

  private ImmutableList<String> getCompilerSuffix() {
    Preconditions.checkState(operation.isCompile());
    ImmutableList.Builder<String> suffix = ImmutableList.builder();
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      suffix.addAll(getPreprocessorSuffix());
    }
    suffix.addAll(ruleCompilerFlags.get());
    suffix.addAll(
        compiler.get()
            .debugCompilationDirFlags(sanitizer.getCompilationDirectory())
            .or(ImmutableList.<String>of()));
    return suffix.build();
  }

  public ImmutableList<String> getCompileCommandCombinedWithPreprocessBuildRule(
      CxxPreprocessAndCompile preprocessBuildRule) {
    if (!operation.isCompile() ||
        !preprocessBuildRule.operation.isPreprocess()) {
      throw new HumanReadableException(
          "%s is not preprocess rule or %s is not compile rule.",
          preprocessBuildRule,
          this);
    }
    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler.get().getCommandPrefix(getResolver()));
    cmd.addAll(preprocessBuildRule.getPreprocessorPlatformPrefix());
    cmd.addAll(getCompilerPlatformPrefix());
    cmd.addAll(preprocessBuildRule.getPreprocessorSuffix());
    cmd.addAll(getCompilerSuffix());
    cmd.add("-x", preprocessBuildRule.inputType.getLanguage());
    cmd.add("-c");
    cmd.add("-o", output.toString());
    cmd.add(getResolver().getPath(preprocessBuildRule.input).toString());
    return cmd.build();
  }

  public ImmutableList<String> getCommand() {
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      return makeMainStep().getCommand();
    }
    return getCompileCommandCombinedWithPreprocessBuildRule(this);
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @VisibleForTesting
  Optional<ImmutableList<String>> getRulePreprocessorFlags() {
    return rulePreprocessorFlags;
  }

  @VisibleForTesting
  Optional<ImmutableList<String>> getPlatformPreprocessorFlags() {
    return platformPreprocessorFlags;
  }

  @VisibleForTesting
  Optional<ImmutableList<String>> getRuleCompilerFlags() {
    return ruleCompilerFlags;
  }

  @VisibleForTesting
  Optional<ImmutableList<String>> getPlatformCompilerFlags() {
    return platformCompilerFlags;
  }

  public Path getOutput() {
    return output;
  }

  public SourcePath getInput() {
    return input;
  }

  public ImmutableList<CxxHeaders> getIncludes() {
    return includes;
  }

}
