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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxSource.Type;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
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
    implements SupportsDependencyFileRuleKey, CxxIntermediateBuildProduct {
  private static final Logger LOG = Logger.get(CxxPreprocessAndCompile.class);

  private final RelPath output;
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
      DebugPathSanitizer sanitizer,
      boolean withDownwardApi) {
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
            sanitizer,
            withDownwardApi));
    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/" + outputName);
    if (precompiledHeaderRule.isPresent()) {
      Preconditions.checkState(
          preprocessDelegate.isPresent(),
          "Precompiled headers are only used when compilation includes preprocessing.");
    }
    this.precompiledHeaderRule = precompiledHeaderRule;
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "CxxPreprocessAndCompile should not be created with CxxStrip flavors");
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "CxxPreprocessAndCompile %s should not be created with LinkerMapMode flavor (%s)",
        this,
        LinkerMapMode.FLAVOR_DOMAIN);
  }

  @Override
  public String getType() {
    return CxxSourceTypes.toName(getBuildable().inputType) + "_preprocess_and_compile";
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
      DebugPathSanitizer sanitizer,
      boolean withDownwardApi) {
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
        sanitizer,
        withDownwardApi);
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
      DebugPathSanitizer sanitizer,
      boolean withDownwardApi) {
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
        sanitizer,
        withDownwardApi);
  }

  private Path getDepFilePath() {
    return Impl.getDepFilePath(
        getOutputPathResolver().resolvePath(getBuildable().output).getPath());
  }

  private RelPath getRelativeInputPaths(SourcePathResolverAdapter resolver) {
    // For caching purposes, the path passed to the compiler is relativized by the absolute path by
    // the current cell root, so that file references emitted by the compiler would not change if
    // the repo is checked out into different places on disk.
    return getProjectFilesystem().getRootPath().relativize(resolver.getAbsolutePath(getInput()));
  }

  /** Returns the original path of the source file relative to its own project root */
  public String getSourceInputPath(SourcePathResolverAdapter resolver) {
    return resolver.getSourcePathName(getBuildable().targetName, getBuildable().input);
  }

  @VisibleForTesting
  static Path getGcnoPath(Path output) {
    String basename = MorePaths.getNameWithoutExtension(output);
    return output.getParent().resolve(basename + ".gcno");
  }

  @VisibleForTesting
  static AbsPath getGcnoPath(AbsPath output) {
    return AbsPath.of(getGcnoPath(output.getPath()));
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
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolverAdapter pathResolver) {
    return Depfiles.getCoveredByDepFilePredicate(
        getPreprocessorDelegate(), Optional.of(getCompilerDelegate()));
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(
      SourcePathResolverAdapter pathResolver) {
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
                context.getSourcePathResolver(),
                preprocessorDelegate.getHeaderPathNormalizer(context),
                preprocessorDelegate.getHeaderVerification(),
                getDepFilePath(),
                getRelativeInputPaths(context.getSourcePathResolver()).getPath(),
                output.getPath(),
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

  @Override
  public final boolean shouldRespectInputSizeLimitForRemoteExecution() {
    return false;
  }

  public CxxPreprocessAndCompileStep makeMainStep(BuildContext context, boolean useArgFile) {
    return getBuildable()
        .makeMainStep(context, getProjectFilesystem(), getOutputPathResolver(), useArgFile);
  }

  /** Buildable implementation for CxxPreprocessAndCompile. */
  static class Impl extends CxxPreprocessAndCompileBaseBuildable {
    public Impl(
        BuildTarget targetName,
        Optional<PreprocessorDelegate> preprocessDelegate,
        CompilerDelegate compilerDelegate,
        String outputName,
        SourcePath input,
        Optional<CxxPrecompiledHeader> precompiledHeaderRule,
        Type inputType,
        DebugPathSanitizer sanitizer,
        boolean withDownwardApi) {
      super(
          targetName,
          preprocessDelegate,
          compilerDelegate,
          outputName,
          input,
          precompiledHeaderRule,
          inputType,
          sanitizer,
          withDownwardApi);
    }

    CxxPreprocessAndCompileStep makeMainStep(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        boolean useArgfile) {
      SourcePathResolverAdapter resolver = context.getSourcePathResolver();
      HeaderPathNormalizer headerPathNormalizer = getHeaderPathNormalizer(context);

      CxxToolFlags preprocessorDelegateFlags = getCxxToolFlags(resolver);

      ImmutableList<Arg> arguments = getArguments(filesystem, preprocessorDelegateFlags);

      RelPath relativeInputPath = filesystem.relativize(resolver.getAbsolutePath(input));
      RelPath resolvedOutput = outputPathResolver.resolvePath(output);

      return new CxxPreprocessAndCompileStep(
          filesystem,
          getOperation(),
          resolvedOutput.getPath(),
          getDepFile(resolvedOutput.getPath()),
          relativeInputPath.getPath(),
          inputType,
          makeCommand(resolver, arguments),
          context.getSourcePathResolver(),
          headerPathNormalizer,
          sanitizer,
          outputPathResolver.getTempPath().getPath(),
          useArgfile,
          compilerDelegate.getPreArgfileArgs(),
          compilerDelegate.getCompiler(),
          Optional.of(
              ImmutableCxxLogInfo.ofImpl(
                  Optional.ofNullable(targetName),
                  Optional.ofNullable(relativeInputPath.getPath()),
                  Optional.of(resolvedOutput.getPath()))),
          withDownwardApi);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      RelPath resolvedOutput = outputPathResolver.resolvePath(output);
      preprocessDelegate
          .flatMap(PreprocessorDelegate::checkConflictingHeaders)
          .ifPresent(
              result ->
                  result.throwHumanReadableExceptionWithContext(
                      targetName.getFullyQualifiedName()));
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
                public StepExecutionResult execute(StepExecutionContext executionContext) {
                  AbsPath outputPath = filesystem.getRootPath().resolve(resolvedOutput);
                  if (!Files.exists(outputPath.getPath())) {
                    LOG.error(
                        new NoSuchFileException(outputPath.toString()),
                        "Compile step was successful but output file: "
                            + outputPath.toString()
                            + " does not exist.");
                    return StepExecutionResults.ERROR;
                  }
                  return StepExecutionResults.SUCCESS;
                }
              })
          .build();
    }
  }
}
