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
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** A build rule which preprocesses and/or compiles a C/C++ source in a single step. */
public class CxxPreprocessAndCompile extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {

  @AddToRuleKey private final CxxPreprocessAndCompileStep.Operation operation;
  @AddToRuleKey private final Optional<PreprocessorDelegate> preprocessDelegate;
  @AddToRuleKey private final CompilerDelegate compilerDelegate;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final SourcePath input;
  private final Optional<CxxPrecompiledHeader> precompiledHeaderRule;
  private final CxxSource.Type inputType;
  private final DebugPathSanitizer compilerSanitizer;
  private final DebugPathSanitizer assemblerSanitizer;
  private final Optional<SymlinkTree> sandboxTree;

  @VisibleForTesting
  public CxxPreprocessAndCompile(
      BuildRuleParams params,
      CxxPreprocessAndCompileStep.Operation operation,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    super(params);
    this.sandboxTree = sandboxTree;
    Preconditions.checkState(operation.isPreprocess() == preprocessDelegate.isPresent());
    if (precompiledHeaderRule.isPresent()) {
      Preconditions.checkState(
          operation == CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE,
          "Precompiled headers can only be used for compile operations.");
    }
    this.operation = operation;
    this.preprocessDelegate = preprocessDelegate;
    this.compilerDelegate = compilerDelegate;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.precompiledHeaderRule = precompiledHeaderRule;
    this.compilerSanitizer = compilerSanitizer;
    this.assemblerSanitizer = assemblerSanitizer;
    performChecks(params);
  }

  private void performChecks(BuildRuleParams params) {
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /** @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source. */
  public static CxxPreprocessAndCompile compile(
      BuildRuleParams params,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        Optional.empty(),
        compilerDelegate,
        output,
        input,
        inputType,
        Optional.empty(),
        compilerSanitizer,
        assemblerSanitizer,
        sandboxTree);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildRuleParams params,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      Path output,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer compilerSanitizer,
      DebugPathSanitizer assemblerSanitizer,
      Optional<SymlinkTree> sandboxTree) {
    return new CxxPreprocessAndCompile(
        params,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE,
        Optional.of(preprocessorDelegate),
        compilerDelegate,
        output,
        input,
        inputType,
        precompiledHeaderRule,
        compilerSanitizer,
        assemblerSanitizer,
        sandboxTree);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    // If a sanitizer is being used for compilation, we need to record the working directory in
    // the rule key, as changing this changes the generated object file.
    if (operation == CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE) {
      sink.setReflectively("compilationDirectory", compilerSanitizer.getCompilationDirectory());
    }
    if (sandboxTree.isPresent()) {
      ImmutableMap<Path, SourcePath> links = sandboxTree.get().getLinks();
      for (Path path : ImmutableSortedSet.copyOf(links.keySet())) {
        SourcePath source = links.get(path);
        sink.setReflectively("sandbox(" + path.toString() + ")", source);
      }
    }
    if (precompiledHeaderRule.isPresent()) {
      sink.setReflectively("precompiledHeaderRuleInput", precompiledHeaderRule.get().getInput());
    }
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  @VisibleForTesting
  CxxPreprocessAndCompileStep makeMainStep(
      SourcePathResolver resolver, Path scratchDir, boolean useArgfile) {

    // If we're compiling, this will just be empty.
    HeaderPathNormalizer headerPathNormalizer =
        preprocessDelegate.isPresent()
            ? preprocessDelegate.get().getHeaderPathNormalizer()
            : HeaderPathNormalizer.empty(resolver);

    Optional<CxxPreprocessAndCompileStep.ToolCommand> preprocessorCommand;
    if (operation.isPreprocess()) {
      preprocessorCommand =
          Optional.of(
              new CxxPreprocessAndCompileStep.ToolCommand(
                  preprocessDelegate.get().getCommandPrefix(),
                  preprocessDelegate
                      .get()
                      .getArguments(compilerDelegate.getCompilerFlags(), Optional.empty()),
                  preprocessDelegate.get().getEnvironment(),
                  preprocessDelegate.get().getFlagsForColorDiagnostics()));
    } else {
      preprocessorCommand = Optional.empty();
    }

    Optional<CxxPreprocessAndCompileStep.ToolCommand> compilerCommand;
    if (operation.isCompile()) {
      ImmutableList<String> arguments;
      if (operation == CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE) {
        arguments =
            compilerDelegate.getArguments(
                preprocessDelegate.get().getFlagsWithSearchPaths(precompiledHeaderRule),
                getBuildTarget().getCellPath());
      } else {
        arguments =
            compilerDelegate.getArguments(CxxToolFlags.of(), getBuildTarget().getCellPath());
      }
      compilerCommand =
          Optional.of(
              new CxxPreprocessAndCompileStep.ToolCommand(
                  compilerDelegate.getCommandPrefix(),
                  arguments,
                  compilerDelegate.getEnvironment(),
                  compilerDelegate.getFlagsForColorDiagnostics()));
    } else {
      compilerCommand = Optional.empty();
    }

    return new CxxPreprocessAndCompileStep(
        getProjectFilesystem(),
        operation,
        output,
        getDepFilePath(),
        getRelativeInputPath(resolver),
        inputType,
        preprocessorCommand,
        compilerCommand,
        headerPathNormalizer,
        compilerSanitizer,
        assemblerSanitizer,
        scratchDir,
        useArgfile,
        compilerDelegate.getCompiler());
  }

  public Path getRelativeInputPath(SourcePathResolver resolver) {
    // For caching purposes, the path passed to the compiler is relativized by the absolute path by
    // the current cell root, so that file references emitted by the compiler would not change if
    // the repo is checked out into different places on disk.
    return getProjectFilesystem().getRootPath().relativize(resolver.getAbsolutePath(input));
  }

  @Override
  public String getType() {
    return "cxx_preprocess_compile";
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return new ImmutableList.Builder<Step>()
        .add(MkdirStep.of(getProjectFilesystem(), output.getParent()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getScratchPath()))
        .add(
            makeMainStep(
                context.getSourcePathResolver(),
                getScratchPath(),
                compilerDelegate.isArgFileSupported()))
        .build();
  }

  private Path getScratchPath() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-tmp");
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return preprocessDelegate;
  }

  // Used for compdb
  public ImmutableList<String> getCommand(SourcePathResolver pathResolver) {
    if (operation == CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE) {
      return makeMainStep(pathResolver, getScratchPath(), false).getCommand();
    }

    PreprocessorDelegate effectivePreprocessorDelegate = preprocessDelegate.get();
    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(
        compilerDelegate.getCommand(
            effectivePreprocessorDelegate.getFlagsWithSearchPaths(/*pch*/ Optional.empty()),
            getBuildTarget().getCellPath()));
    // use the input of the preprocessor, since the fact that this is going through preprocessor is
    // hidden to compdb.
    cmd.add("-x", inputType.getLanguage());
    cmd.add("-c");
    cmd.add("-o", output.toString());
    cmd.add(pathResolver.getAbsolutePath(input).toString());
    return cmd.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  public SourcePath getInput() {
    return input;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return compilerDelegate.isDependencyFileSupported();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    if (preprocessDelegate.isPresent()) {
      return preprocessDelegate.get().getCoveredByDepFilePredicate();
    }
    return (SourcePath path) -> true;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
      throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // If present, include all inputs coming from the preprocessor tool.
    if (preprocessDelegate.isPresent()) {
      Iterable<Path> depFileLines;
      try {
        depFileLines =
            Depfiles.parseAndOutputBuckCompatibleDepfile(
                context.getEventBus(),
                getProjectFilesystem(),
                preprocessDelegate.get().getHeaderPathNormalizer(),
                preprocessDelegate.get().getHeaderVerification(),
                getDepFilePath(),
                getRelativeInputPath(context.getSourcePathResolver()),
                output);
      } catch (Depfiles.HeaderVerificationException e) {
        throw new HumanReadableException(e);
      }

      inputs.addAll(preprocessDelegate.get().getInputsAfterBuildingLocally(depFileLines));
    }

    // If present, include all inputs coming from the compiler tool.
    if (operation.isCompile()) {
      inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());
    }

    if (precompiledHeaderRule.isPresent()) {
      CxxPrecompiledHeader pch = precompiledHeaderRule.get();
      inputs.addAll(pch.getInputsAfterBuildingLocally(context));
    }

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }
}
