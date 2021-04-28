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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Predicate;

/** Generate the CFG for a source file */
class CxxInferCapture extends AbstractBuildRule implements SupportsDependencyFileRuleKey {

  private final ImmutableSortedSet<BuildRule> buildDeps;

  @AddToRuleKey private final CompilerDelegate compilerDelegate;
  @AddToRuleKey private final InferBuckConfig inferConfig;
  @AddToRuleKey private final CxxToolFlags preprocessorFlags;
  @AddToRuleKey private final CxxToolFlags compilerFlags;
  @AddToRuleKey private final SourcePath input;
  private final CxxSource.Type inputType;
  private final Optional<PreInclude> preInclude;

  @AddToRuleKey(stringify = true)
  private final RelPath output;

  @AddToRuleKey private final PreprocessorDelegate preprocessorDelegate;

  private final RelPath resultsDir;

  @AddToRuleKey private final boolean withDownwardApi;
  private BuildableSupport.DepsSupplier depsSupplier;

  CxxInferCapture(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      ImmutableSortedSet<BuildRule> buildDeps,
      CompilerDelegate compilerDelegate,
      CxxToolFlags preprocessorFlags,
      CxxToolFlags compilerFlags,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<PreInclude> preInclude,
      String outputName,
      PreprocessorDelegate preprocessorDelegate,
      InferBuckConfig inferConfig,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = buildDeps;
    this.compilerDelegate = compilerDelegate;
    this.preprocessorFlags = preprocessorFlags;
    this.compilerFlags = compilerFlags;
    this.input = input;
    this.inputType = inputType;
    this.preInclude = preInclude;
    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/" + outputName);
    this.preprocessorDelegate = preprocessorDelegate;
    this.inferConfig = inferConfig;
    this.withDownwardApi = withDownwardApi;
    this.resultsDir =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), this.getBuildTarget(), "infer-out-%s");
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, graphBuilder);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.copyOf(Sets.union(buildDeps, depsSupplier.get()));
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
  }

  private CxxToolFlags getSearchPathFlags(SourcePathResolverAdapter pathResolver) {
    return preprocessorDelegate.getFlagsWithSearchPaths(
        /* no pch */ Optional.empty(), pathResolver);
  }

  private ImmutableList<String> getFrontendCommand() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    return commandBuilder
        .add(this.inferConfig.getInferTopLevel().toString())
        .add("capture")
        .add("--results-dir", resultsDir.toString())
        .add("--project-root", getProjectFilesystem().getRootPath().toString())
        .add("--")
        .add("clang")
        .add("@" + getArgfile())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    preprocessorDelegate
        .checkConflictingHeaders()
        .ifPresent(result -> result.throwHumanReadableExceptionWithContext(getBuildTarget()));

    ImmutableList<String> frontendCommand = getFrontendCommand();

    buildableContext.recordArtifact(
        context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput()).getPath());

    RelPath inputRelativePath = context.getSourcePathResolver().getCellUnsafeRelPath(input);
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), resultsDir)))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(new WriteArgFileStep(context.getSourcePathResolver(), inputRelativePath))
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(),
                withDownwardApi,
                frontendCommand,
                ImmutableMap.of()))
        .build();
  }

  @Override
  public ExplicitBuildTargetSourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), resultsDir);
  }

  public AbsPath getAbsolutePathToOutput() {
    return getProjectFilesystem().resolve(resultsDir);
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolverAdapter pathResolver) {
    return Depfiles.getCoveredByDepFilePredicate(
        Optional.of(preprocessorDelegate), Optional.empty());
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(
      SourcePathResolverAdapter pathResolver) {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {

    ImmutableList<Path> dependencies;
    try {
      dependencies =
          Depfiles.parseAndVerifyDependencies(
              context.getEventBus(),
              getProjectFilesystem(),
              context.getSourcePathResolver(),
              preprocessorDelegate.getHeaderPathNormalizer(context),
              HeaderVerification.of(HeaderVerification.Mode.IGNORE),
              getDepFilePath(),
              context.getSourcePathResolver().getCellUnsafeRelPath(input).getPath(),
              output.getPath(),
              DependencyTrackingMode.MAKEFILE,
              false);
    } catch (Depfiles.HeaderVerificationException e) {
      throw new HumanReadableException(e);
    }

    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // include all inputs coming from the preprocessor tool.
    inputs.addAll(preprocessorDelegate.getInputsAfterBuildingLocally(dependencies, context));

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private Path getArgfile() {
    return output
        .getFileSystem()
        .getPath(output.getParent().resolve("infer-capture.argsfile").toString());
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output + ".dep");
  }

  private class WriteArgFileStep implements Step {

    private final SourcePathResolverAdapter pathResolver;
    private final Path inputRelativePath;

    public WriteArgFileStep(SourcePathResolverAdapter pathResolver, Path inputRelativePath) {
      this.pathResolver = pathResolver;
      this.inputRelativePath = inputRelativePath;
    }

    public WriteArgFileStep(SourcePathResolverAdapter pathResolver, RelPath inputRelativePath) {
      this(pathResolver, inputRelativePath.getPath());
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      getProjectFilesystem()
          .writeLinesToPath(
              Iterables.transform(getCompilerArgs(), Escaper.ARGFILE_ESCAPER::apply), getArgfile());
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write-argfile";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return "Write argfile for clang";
    }

    private ImmutableList<String> getPreIncludeArgs() {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      if (preInclude.isPresent()) {
        Preprocessor pp = preprocessorDelegate.getPreprocessor();
        PreInclude pre = preInclude.get();
        builder.addAll(pp.prefixHeaderArgs(pre.getAbsoluteHeaderPath()));
      }
      return builder.build();
    }

    private ImmutableList<String> getCompilerArgs() {
      ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
      return commandBuilder
          .add("-MD", "-MF", getDepFilePath().toString())
          .addAll(getPreIncludeArgs())
          .addAll(
              Arg.stringify(
                  CxxToolFlags.concat(
                          preprocessorFlags, getSearchPathFlags(pathResolver), compilerFlags)
                      .getAllFlags(),
                  pathResolver))
          .add("-x", inputType.getLanguage())
          .add("-o", output.toString()) // TODO(martinoluca): Use -fsyntax-only for better perf
          .add("-c")
          .add(inputRelativePath.toString())
          .build();
    }
  }
}
