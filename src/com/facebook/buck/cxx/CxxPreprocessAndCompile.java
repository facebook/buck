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
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A build rule which preprocesses and/or compiles a C/C++ source in a single step.
 */
public class CxxPreprocessAndCompile
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {

  @AddToRuleKey
  private final CxxPreprocessAndCompileStep.Operation operation;
  @AddToRuleKey
  private final Optional<PreprocessorDelegate> preprocessDelegate;
  @AddToRuleKey
  private final Optional<Compiler> compiler;
  private final Optional<ImmutableList<String>> platformCompilerFlags;
  private final Optional<ImmutableList<String>> ruleCompilerFlags;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  private final DebugPathSanitizer sanitizer;

  @VisibleForTesting
  public CxxPreprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      CxxPreprocessAndCompileStep.Operation operation,
      Optional<PreprocessorDelegate> preprocessDelegate,
      Optional<Compiler> compiler,
      Optional<ImmutableList<String>> platformCompilerFlags,
      Optional<ImmutableList<String>> ruleCompilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    Preconditions.checkState(operation.isPreprocess() == preprocessDelegate.isPresent());
    Preconditions.checkState(operation.isCompile() == compiler.isPresent());
    Preconditions.checkState(operation.isCompile() == platformCompilerFlags.isPresent());
    Preconditions.checkState(operation.isCompile() == ruleCompilerFlags.isPresent());
    this.operation = operation;
    this.preprocessDelegate = preprocessDelegate;
    this.compiler = compiler;
    this.platformCompilerFlags = platformCompilerFlags;
    this.ruleCompilerFlags = ruleCompilerFlags;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
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
        Optional.<PreprocessorDelegate>absent(),
        Optional.of(compiler),
        Optional.of(platformFlags),
        Optional.of(ruleFlags),
        output,
        input,
        inputType,
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses the given source.
   */
  public static CxxPreprocessAndCompile preprocess(
      BuildRuleParams params,
      SourcePathResolver resolver,
      PreprocessorDelegate preprocessorDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        Optional.of(preprocessorDelegate),
        Optional.<Compiler>absent(),
        Optional.<ImmutableList<String>>absent(),
        Optional.<ImmutableList<String>>absent(),
        output,
        input,
        inputType,
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      PreprocessorDelegate preprocessorDelegate,
      Compiler compiler,
      ImmutableList<String> platformCompilerFlags,
      ImmutableList<String> ruleCompilerFlags,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer,
      CxxPreprocessMode strategy) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        (strategy == CxxPreprocessMode.PIPED
            ? CxxPreprocessAndCompileStep.Operation.PIPED_PREPROCESS_AND_COMPILE
            : CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO),
        Optional.of(preprocessorDelegate),
        Optional.of(compiler),
        Optional.of(platformCompilerFlags),
        Optional.of(ruleCompilerFlags),
        output,
        input,
        inputType,
        sanitizer);
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    builder.setReflectively(
        "platformCompilerFlags",
        sanitizer.sanitizeFlags(platformCompilerFlags));
    builder.setReflectively(
        "ruleCompilerFlags",
        sanitizer.sanitizeFlags(ruleCompilerFlags));

    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      builder.setReflectively("compilationDirectory", sanitizer.getCompilationDirectory());
    }

    return builder;
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep() {

    // If we're compiling, this will just be empty.
    ImmutableMap<Path, Path> replacementPaths;
    try {
      replacementPaths = preprocessDelegate.isPresent()
          ? preprocessDelegate.get().getReplacementPaths()
          : ImmutableMap.<Path, Path>of();
    } catch (CxxHeaders.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }

    Optional<ImmutableList<String>> preprocessorCommand;
    Optional<ImmutableMap<String, String>> preprocessorEnvironment;

    if (preprocessDelegate.isPresent()) {
      preprocessorCommand = Optional.of(preprocessDelegate.get().getPreprocessorCommand());
      preprocessorEnvironment = Optional.of(preprocessDelegate.get().getPreprocessorEnvironment());
    } else {
      preprocessorCommand = Optional.absent();
      preprocessorEnvironment = Optional.absent();
    }

    Optional<ImmutableList<String>> compilerCommand;
    Optional<ImmutableMap<String, String>> compilerEnvironment;
    if (compiler.isPresent()) {
      compilerCommand = Optional.of(
          ImmutableList.<String>builder()
              .addAll(compiler.get().getCommandPrefix(getResolver()))
              .addAll(getCompilerPlatformPrefix())
              .addAll(getCompilerSuffix())
              .build());
      compilerEnvironment = Optional.of(compiler.get().getEnvironment(getResolver()));
    } else {
      compilerCommand = Optional.absent();
      compilerEnvironment = Optional.absent();
    }

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        operation,
        output,
        getDepFilePath(),
        getResolver().deprecatedGetPath(input),
        inputType,
        preprocessorEnvironment,
        preprocessorCommand,
        compilerEnvironment,
        compilerCommand,
        replacementPaths,
        sanitizer,
        preprocessDelegate.isPresent() ?
            preprocessDelegate.get().getPreprocessorExtraLineProcessor() :
            Optional.<Function<String, Iterable<String>>>absent());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        makeMainStep());
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return preprocessDelegate;
  }

  private ImmutableList<String> getCompilerPlatformPrefix() {
    Preconditions.checkState(operation.isCompile());
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      flags.addAll(preprocessDelegate.get().getPreprocessorPlatformPrefix());
    }
    flags.addAll(platformCompilerFlags.get());
    return flags.build();
  }

  private ImmutableList<String> getCompilerSuffix() {
    Preconditions.checkState(operation.isCompile());
    ImmutableList.Builder<String> suffix = ImmutableList.builder();
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      suffix.addAll(preprocessDelegate.get().getPreprocessorSuffix());
    }
    suffix.addAll(ruleCompilerFlags.get());
    suffix.addAll(
        compiler.get()
            .debugCompilationDirFlags(sanitizer.getCompilationDirectory())
            .or(ImmutableList.<String>of()));
    return suffix.build();
  }

  // Used for compdb
  public ImmutableList<String> getCommand(
      Optional<CxxPreprocessAndCompile> externalPreprocessRule) {
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      return makeMainStep().getCommand();
    }

    CxxPreprocessAndCompile preprocessRule = externalPreprocessRule.or(this);
    if (!preprocessRule.preprocessDelegate.isPresent()) {
      throw new HumanReadableException(
          "Neither %s nor %s handles preprocessing.",
          externalPreprocessRule,
          this);
    }
    PreprocessorDelegate effectivePreprocessorDelegate = preprocessRule.preprocessDelegate.get();
    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler.get().getCommandPrefix(getResolver()));
    cmd.addAll(effectivePreprocessorDelegate.getPreprocessorPlatformPrefix());
    cmd.addAll(getCompilerPlatformPrefix());
    cmd.addAll(effectivePreprocessorDelegate.getPreprocessorSuffix());
    cmd.addAll(getCompilerSuffix());
    // use the input of the preprocessor, since the fact that this is going through preprocessor is
    // hidden to compdb.
    cmd.add("-x", preprocessRule.inputType.getLanguage());
    cmd.add("-c");
    cmd.add("-o", output.toString());
    cmd.add(getResolver().deprecatedGetPath(preprocessRule.input).toString());
    return cmd.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
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

  @Override
  public boolean useDependencyFileRuleKeys() {
    return operation.isPreprocess();
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // If present, include all inputs coming from the preprocessor tool.
    if (preprocessDelegate.isPresent()) {
      inputs.addAll(preprocessDelegate.get().getInputsAfterBuildingLocally(readDepFileLines()));
    }

    // If present, include all inputs coming from the compiler tool.
    if (compiler.isPresent()) {
      inputs.addAll(
          Ordering.natural().immutableSortedCopy(compiler.get().getInputs()));
    }

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }

}
