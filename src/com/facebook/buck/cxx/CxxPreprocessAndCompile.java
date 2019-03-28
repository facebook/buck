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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.AbstractCxxSource.Type;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/** A build rule which preprocesses and/or compiles a C/C++ source in a single step. */
public class CxxPreprocessAndCompile extends ModernBuildRule<CxxPreprocessAndCompile.Impl>
    implements SupportsDependencyFileRuleKey {
  private static final Logger LOG = Logger.get(CxxPreprocessAndCompile.class);

  private final Path output;
  private final Optional<CxxPrecompiledHeader> precompiledHeaderRule;

  private CxxPreprocessAndCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<PreprocessorDelegate> preprocessDelegate,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            buildTarget,
            preprocessDelegate,
            compilerDelegate,
            outputName,
            input,
            precompiledHeaderRule,
            inputType,
            sanitizer));
    this.output =
        BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/" + outputName);
    if (precompiledHeaderRule.isPresent()) {
      Preconditions.checkState(
          preprocessDelegate.isPresent(),
          "Precompiled headers are only used when compilation includes preprocessing.");
    }
    this.precompiledHeaderRule = precompiledHeaderRule;
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  /** @return a {@link CxxPreprocessAndCompile} step that compiles the given preprocessed source. */
  public static CxxPreprocessAndCompile compile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      Type inputType,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        Optional.empty(),
        compilerDelegate,
        outputName,
        input,
        inputType,
        Optional.empty(),
        sanitizer);
  }

  /**
   * @return a {@link CxxPreprocessAndCompile} step that preprocesses and compiles the given source.
   */
  public static CxxPreprocessAndCompile preprocessAndCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      PreprocessorDelegate preprocessorDelegate,
      CompilerDelegate compilerDelegate,
      String outputName,
      SourcePath input,
      Type inputType,
      Optional<CxxPrecompiledHeader> precompiledHeaderRule,
      DebugPathSanitizer sanitizer) {
    return new CxxPreprocessAndCompile(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        Optional.of(preprocessorDelegate),
        compilerDelegate,
        outputName,
        input,
        inputType,
        precompiledHeaderRule,
        sanitizer);
  }

  private Path getDepFilePath() {
    return Impl.getDepFilePath(getOutputPathResolver().resolvePath(getBuildable().output));
  }

  public Path getRelativeInputPath(SourcePathResolver resolver) {
    // For caching purposes, the path passed to the compiler is relativized by the absolute path by
    // the current cell root, so that file references emitted by the compiler would not change if
    // the repo is checked out into different places on disk.
    return getProjectFilesystem().getRootPath().relativize(resolver.getAbsolutePath(getInput()));
  }

  @VisibleForTesting
  static Path getGcnoPath(Path output) {
    String basename = MorePaths.getNameWithoutExtension(output);
    return output.getParent().resolve(basename + ".gcno");
  }

  @VisibleForTesting
  Optional<PreprocessorDelegate> getPreprocessorDelegate() {
    return getBuildable().preprocessDelegate;
  }

  CompilerDelegate getCompilerDelegate() {
    return getBuildable().compilerDelegate;
  }

  /** Returns the compilation command (used for compdb). */
  public ImmutableList<String> getCommand(BuildContext context) {
    return getBuildable()
        .makeMainStep(context, getProjectFilesystem(), getOutputPathResolver(), false)
        .getCommand();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public SourcePath getInput() {
    return getBuildable().input;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return CxxSourceTypes.supportsDepFiles(getBuildable().inputType);
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return Depfiles.getCoveredByDepFilePredicate(
        getPreprocessorDelegate(), Optional.of(getCompilerDelegate()));
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();
    CompilerDelegate compilerDelegate = getBuildable().compilerDelegate;

    // If present, include all inputs coming from the preprocessor tool.
    if (getPreprocessorDelegate().isPresent()) {
      PreprocessorDelegate preprocessorDelegate = getPreprocessorDelegate().get();
      Iterable<Path> dependencies;
      try {
        dependencies =
            Depfiles.parseAndVerifyDependencies(
                context.getEventBus(),
                getProjectFilesystem(),
                preprocessorDelegate.getHeaderPathNormalizer(context),
                preprocessorDelegate.getHeaderVerification(),
                getDepFilePath(),
                getRelativeInputPath(context.getSourcePathResolver()),
                output,
                compilerDelegate.getDependencyTrackingMode(),
                compilerDelegate.getCompiler().getUseUnixPathSeparator());
      } catch (Depfiles.HeaderVerificationException e) {
        throw new HumanReadableException(e);
      }

      inputs.addAll(preprocessorDelegate.getInputsAfterBuildingLocally(dependencies, context));
    }

    // If present, include all inputs coming from the compiler tool.
    inputs.addAll(compilerDelegate.getInputsAfterBuildingLocally());

    // In the non-precompiled case, the headers are properly reflected in our other inputs.
    if (precompiledHeaderRule.isPresent()
        && getBuildable().precompiledHeaderData.get().isPrecompiled()) {
      CxxPrecompiledHeader pch = precompiledHeaderRule.get();
      inputs.addAll(pch.getInputsAfterBuildingLocally(context, cellPathResolver));
    }

    // Add the input.
    inputs.add(getInput());

    return inputs.build();
  }

  public CxxPreprocessAndCompileStep makeMainStep(BuildContext context, boolean useArgFile) {
    return getBuildable()
        .makeMainStep(context, getProjectFilesystem(), getOutputPathResolver(), useArgFile);
  }

  /** Buildable implementation for CxxPreprocessAndCompile. */
  static class Impl implements Buildable {
    @AddToRuleKey private final BuildTarget targetName;
    /** The presence or absence of this field denotes whether the input needs to be preprocessed. */
    @AddToRuleKey private final Optional<PreprocessorDelegate> preprocessDelegate;

    @AddToRuleKey private final CompilerDelegate compilerDelegate;
    @AddToRuleKey private final DebugPathSanitizer sanitizer;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final SourcePath input;
    @AddToRuleKey private final CxxSource.Type inputType;

    @AddToRuleKey private final Optional<PrecompiledHeaderData> precompiledHeaderData;

    public Impl(
        BuildTarget targetName,
        Optional<PreprocessorDelegate> preprocessDelegate,
        CompilerDelegate compilerDelegate,
        String outputName,
        SourcePath input,
        Optional<CxxPrecompiledHeader> precompiledHeaderRule,
        Type inputType,
        DebugPathSanitizer sanitizer) {
      this.targetName = targetName;
      this.preprocessDelegate = preprocessDelegate;
      this.compilerDelegate = compilerDelegate;
      this.sanitizer = sanitizer;
      this.output = new OutputPath(outputName);
      this.input = input;
      this.inputType = inputType;
      this.precompiledHeaderData = precompiledHeaderRule.map(CxxPrecompiledHeader::getData);
    }

    CxxPreprocessAndCompileStep makeMainStep(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        boolean useArgfile) {
      SourcePathResolver resolver = context.getSourcePathResolver();
      // If we're compiling, this will just be empty.
      HeaderPathNormalizer headerPathNormalizer =
          preprocessDelegate
              .map(x -> x.getHeaderPathNormalizer(context))
              .orElseGet(() -> HeaderPathNormalizer.empty(resolver));

      CxxToolFlags preprocessorDelegateFlags =
          preprocessDelegate
              .map(delegate -> delegate.getFlagsWithSearchPaths(precompiledHeaderData, resolver))
              .orElseGet(CxxToolFlags::of);

      ImmutableList<Arg> arguments =
          compilerDelegate.getArguments(preprocessorDelegateFlags, filesystem.getRootPath());

      Path relativeInputPath = filesystem.relativize(resolver.getAbsolutePath(input));
      Path resolvedOutput = outputPathResolver.resolvePath(output);

      return new CxxPreprocessAndCompileStep(
          filesystem,
          preprocessDelegate.isPresent()
              ? CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE
              : CxxPreprocessAndCompileStep.Operation.COMPILE,
          resolvedOutput,
          // Use a depfile if there's a preprocessing stage, this logic should be kept in sync with
          // getInputsAfterBuildingLocally.
          CxxSourceTypes.supportsDepFiles(inputType)
              ? preprocessDelegate.map(ignored -> getDepFilePath(resolvedOutput))
              : Optional.empty(),
          relativeInputPath,
          inputType,
          new CxxPreprocessAndCompileStep.ToolCommand(
              compilerDelegate.getCommandPrefix(resolver),
              Arg.stringify(arguments, resolver),
              compilerDelegate.getEnvironment(resolver)),
          headerPathNormalizer,
          sanitizer,
          outputPathResolver.getTempPath(),
          useArgfile,
          compilerDelegate.getCompiler(),
          Optional.of(
              CxxLogInfo.builder()
                  .setTarget(targetName)
                  .setSourcePath(relativeInputPath)
                  .setOutputPath(resolvedOutput)
                  .build()));
    }

    static Path getDepFilePath(Path outputPath) {
      return outputPath.getParent().resolve(outputPath.getFileName() + ".dep");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path resolvedOutput = outputPathResolver.resolvePath(output);
      preprocessDelegate
          .flatMap(PreprocessorDelegate::checkConflictingHeaders)
          .ifPresent(result -> result.throwHumanReadableExceptionWithContext(targetName));
      return new ImmutableList.Builder<Step>()
          .add(
              MkdirStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      context.getBuildCellRootPath(), filesystem, resolvedOutput.getParent())))
          .add(
              makeMainStep(
                  context, filesystem, outputPathResolver, compilerDelegate.isArgFileSupported()))
          .add(
              new AbstractExecutionStep("verify_cxx_outputs") {
                @Override
                public StepExecutionResult execute(ExecutionContext executionContext)
                    throws IOException, InterruptedException {
                  Path outputPath =
                      filesystem.getRootPath().toAbsolutePath().resolve(resolvedOutput);
                  if (!Files.exists(outputPath)) {
                    LOG.warn(
                        new NoSuchFileException(outputPath.toString()),
                        "Compile step was successful but output file: "
                            + outputPath.toString()
                            + " does not exist.");
                  }
                  return StepExecutionResult.of(0, Optional.empty());
                }
              })
          .build();
    }
  }
}
