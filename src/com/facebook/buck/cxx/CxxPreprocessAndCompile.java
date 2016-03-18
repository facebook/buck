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

import com.facebook.buck.model.BuildTargets;
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
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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
  private final CompilerDelegate compilerDelegate;
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
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    Preconditions.checkState(operation.isPreprocess() == preprocessDelegate.isPresent());
    this.operation = operation;
    this.preprocessDelegate = preprocessDelegate;
    this.compilerDelegate = compilerDelegate;
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
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        Optional.<PreprocessorDelegate>absent(),
        compilerDelegate,
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
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        params,
        resolver,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        Optional.of(preprocessorDelegate),
        compilerDelegate,
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
      CompilerDelegate compilerDelegate,
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
        compilerDelegate,
        output,
        input,
        inputType,
        sanitizer);
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
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
  CxxPreprocessAndCompileStep makeMainStep(Path scratchDir) {

    // If we're compiling, this will just be empty.
    ImmutableMap<Path, Path> replacementPaths;
    try {
      replacementPaths = preprocessDelegate.isPresent()
          ? preprocessDelegate.get().getReplacementPaths()
          : ImmutableMap.<Path, Path>of();
    } catch (CxxHeaders.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(getBuildTarget());
    }

    Optional<CxxPreprocessAndCompileStep.ToolCommand> preprocessorCommand;

    if (preprocessDelegate.isPresent()) {
      preprocessorCommand = Optional.of(
          new CxxPreprocessAndCompileStep.ToolCommand(
              getPreprocessorDelegate().get().getCommand(compilerDelegate.getCompilerFlags()),
              preprocessDelegate.get().getEnvironment(),
              preprocessDelegate.get().getFlagsForColorDiagnostics()));
    } else {
      preprocessorCommand = Optional.absent();
    }

    Optional<CxxPreprocessAndCompileStep.ToolCommand> compilerCommand;
    if (operation.isCompile()) {
      compilerCommand = Optional.of(
          new CxxPreprocessAndCompileStep.ToolCommand(
              compilerDelegate.getCommand(
                  operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO
                      ? preprocessDelegate.get().getFlagsWithSearchPaths()
                      : CxxToolFlags.of()),
              compilerDelegate.getEnvironment(),
              compilerDelegate.getFlagsForColorDiagnostics()));
    } else {
      compilerCommand = Optional.absent();
    }

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        operation,
        output,
        getDepFilePath(),
        getResolver().deprecatedGetPath(input),
        inputType,
        preprocessorCommand,
        compilerCommand,
        replacementPaths,
        sanitizer,
        preprocessDelegate.isPresent() ?
            preprocessDelegate.get().getPreprocessorExtraLineProcessor() :
            Optional.<Function<String, Iterable<String>>>absent(),
        scratchDir);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Path scratchDir = BuildTargets.getScratchPath(getBuildTarget(), "%s-tmp");
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        makeMainStep(scratchDir));
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return preprocessDelegate;
  }

  // Used for compdb
  public ImmutableList<String> getCommand(
      Optional<CxxPreprocessAndCompile> externalPreprocessRule) {
    if (operation == CxxPreprocessAndCompileStep.Operation.COMPILE_MUNGE_DEBUGINFO) {
      return makeMainStep(getProjectFilesystem().getRootPath()).getCommand();
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
    cmd.addAll(
        compilerDelegate.getCommand(effectivePreprocessorDelegate.getFlagsWithSearchPaths()));
    // use the input of the preprocessor, since the fact that this is going through preprocessor is
    // hidden to compdb.
    cmd.add("-x", preprocessRule.inputType.getLanguage());
    cmd.add("-c");
    cmd.add("-o", output.toString());
    cmd.add(getResolver().getAbsolutePath(preprocessRule.input).toString());
    return cmd.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
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
    if (operation.isCompile()) {
      inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());
    }

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }

}
