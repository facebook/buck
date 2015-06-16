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
public class CxxPreprocessAndCompile extends AbstractBuildRule implements RuleKeyAppendable {

  @AddToRuleKey
  private final CxxPreprocessAndCompileStep.Operation operation;
  @AddToRuleKey
  private final Optional<Tool> preprocessor;
  private final Optional<ImmutableList<String>> preprocessorFlags;
  @AddToRuleKey
  private final Optional<Tool> compiler;
  private final Optional<ImmutableList<String>> compilerFlags;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final ImmutableList<Path> frameworkRoots;
  @AddToRuleKey
  private final ImmutableList<CxxHeaders> includes;
  private final DebugPathSanitizer sanitizer;

  @VisibleForTesting
  CxxPreprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxPreprocessAndCompileStep.Operation operation,
      Optional<Tool> preprocessor,
      Optional<ImmutableList<String>> preprocessorFlags,
      Optional<Tool> compiler,
      Optional<ImmutableList<String>> compilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    Preconditions.checkState(operation.isPreprocess() == preprocessor.isPresent());
    Preconditions.checkState(operation.isPreprocess() == preprocessorFlags.isPresent());
    Preconditions.checkState(operation.isCompile() == compiler.isPresent());
    Preconditions.checkState(operation.isCompile() == compilerFlags.isPresent());
    this.operation = operation;
    this.preprocessor = preprocessor;
    this.preprocessorFlags = preprocessorFlags;
    this.compiler = compiler;
    this.compilerFlags = compilerFlags;
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
      Tool compiler,
      ImmutableList<String> flags,
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
        Optional.of(compiler),
        Optional.of(flags),
        output,
        input,
        inputType,
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
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
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
      ImmutableList<CxxHeaders> includes,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        Optional.of(preprocessor),
        Optional.of(flags),
        Optional.<Tool>absent(),
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
      ImmutableList<String> preprocessorFlags,
      Tool compiler,
      ImmutableList<String> compilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableList<Path> frameworkRoots,
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
        Optional.of(preprocessorFlags),
        Optional.of(compiler),
        Optional.of(compilerFlags),
        output,
        input,
        inputType,
        includeRoots,
        systemIncludeRoots,
        frameworkRoots,
        includes,
        (strategy == CxxPreprocessMode.COMBINED
            ? sanitizer
            : sanitizer.changePathSize(0)));
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    ImmutableList<String> flags = ImmutableList.<String>builder()
        .addAll(this.preprocessorFlags.or(ImmutableList.<String>of()))
        .addAll(this.compilerFlags.or(ImmutableList.<String>of()))
        .build();
    flags = FluentIterable.from(flags)
        .transform(sanitizer.sanitize(Optional.<Path>absent(), /* expandPaths */ false))
        .toList();
    builder.setReflectively("flags", flags);
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

  private ImmutableList<String> getPreprocessorSuffix() {
    Preconditions.checkState(operation.isPreprocess());
    ImmutableSet.Builder<SourcePath> prefixHeaders = ImmutableSet.builder();
    for (CxxHeaders cxxHeaders : includes) {
      prefixHeaders.addAll(cxxHeaders.getPrefixHeaders());
    }
    return ImmutableList.<String>builder()
        .addAll(preprocessorFlags.get())
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
    suffix.addAll(compilerFlags.get());
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
    cmd.add("-x", preprocessBuildRule.inputType.getLanguage());
    cmd.add("-c");
    cmd.addAll(preprocessBuildRule.getPreprocessorSuffix());
    cmd.addAll(getCompilerSuffix());
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
  Optional<ImmutableList<String>> getPreprocessorFlags() {
    return preprocessorFlags;
  }

  @VisibleForTesting
  Optional<ImmutableList<String>> getCompilerFlags() {
    return compilerFlags;
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
