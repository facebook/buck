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
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ArgFactory;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Generate the CFG for a source file */
class CxxInferCaptureRule extends ModernBuildRule<CxxInferCaptureRule.Impl>
    implements SupportsDependencyFileRuleKey {

  @VisibleForTesting static final RelPath RESULT_DIR_PATH = RelPath.get("infer-out");

  CxxInferCaptureRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      CxxToolFlags preprocessorFlags,
      CxxToolFlags compilerFlags,
      SourcePath input,
      CxxSource.Type inputType,
      Optional<PreInclude> preInclude,
      String outputName,
      CompilerDelegate compilerDelegate,
      InferPlatform inferPlatform,
      PreprocessorDelegate preprocessorDelegate,
      InferConfig inferConfig,
      boolean withDownwardApi) {
    super(
        buildTarget,
        filesystem,
        ruleFinder,
        new Impl(
            buildTarget,
            filesystem,
            preprocessorFlags,
            compilerFlags,
            input,
            compilerDelegate,
            inferPlatform,
            preprocessorDelegate,
            outputName,
            withDownwardApi,
            inputType,
            preInclude,
            inferConfig.executeRemotely()));
  }

  /** Internal Buildable for {@link CxxInferCaptureRule} rule. */
  static class Impl implements Buildable {

    // used to retrieve compiler dependencies
    @AddToRuleKey private final InferPlatform inferPlatform;
    @AddToRuleKey private final CompilerDelegate compilerDelegate;
    @AddToRuleKey private final CxxToolFlags preprocessorFlags;
    @AddToRuleKey private final CxxToolFlags compilerFlags;
    @AddToRuleKey private final SourcePath input;
    @AddToRuleKey private final PreprocessorDelegate preprocessorDelegate;
    @AddToRuleKey private final String buildTargetFullyQualifiedName;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final String inputTypeLanguage;
    @AddToRuleKey private final Optional<String> headerRelPath;
    @AddToRuleKey private final OutputPath resultDirectoryPath;
    @AddToRuleKey private final OutputPath output;

    /** Whether or not infer rules can be executed remotely. Fails serialization if false. */
    @AddToRuleKey
    @CustomFieldBehavior(RemoteExecutionEnabled.class)
    private final boolean executeRemotely;

    Impl(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        CxxToolFlags preprocessorFlags,
        CxxToolFlags compilerFlags,
        SourcePath input,
        CompilerDelegate compilerDelegate,
        InferPlatform inferPlatform,
        PreprocessorDelegate preprocessorDelegate,
        String outputName,
        boolean withDownwardApi,
        CxxSource.Type inputType,
        Optional<PreInclude> preInclude,
        boolean executeRemotely) {
      this.preprocessorFlags = preprocessorFlags;
      this.compilerFlags = compilerFlags;
      this.input = input;
      this.inferPlatform = inferPlatform;
      this.compilerDelegate = compilerDelegate;
      this.preprocessorDelegate = preprocessorDelegate;
      this.buildTargetFullyQualifiedName = buildTarget.getFullyQualifiedName();
      this.withDownwardApi = withDownwardApi;
      this.inputTypeLanguage = inputType.getLanguage();
      this.executeRemotely = executeRemotely;
      this.headerRelPath =
          preInclude
              .map(PreInclude::getAbsoluteHeaderPath)
              .map(filesystem::relativize)
              .map(Objects::toString);
      this.output = new OutputPath(outputName);
      this.resultDirectoryPath = new OutputPath(RESULT_DIR_PATH);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
      preprocessorDelegate
          .checkConflictingHeaders()
          .ifPresent(
              result ->
                  result.throwHumanReadableExceptionWithContext(buildTargetFullyQualifiedName));

      ImmutableList.Builder<IsolatedStep> steps = ImmutableList.builder();
      steps.addAll(
          MakeCleanDirectoryIsolatedStep.of(outputPathResolver.resolvePath(resultDirectoryPath)));
      RelPath outputPath = outputPathResolver.resolvePath(output);
      if (outputPath.getParent() != null) {
        steps.add(MkdirIsolatedStep.of(outputPath.getParent()));
      }

      steps.add(
          new WriteArgFileStep(
              sourcePathResolver.getRelativePath(filesystem, input),
              sourcePathResolver,
              outputPathResolver));

      AbsPath rootPath = filesystem.getRootPath();
      AbsPath buildCellRootPath = context.getBuildCellRootPath();
      steps.add(
          new IsolatedShellStep(
              rootPath,
              ProjectFilesystemUtils.relativize(rootPath, buildCellRootPath),
              withDownwardApi) {

            @Override
            protected ImmutableList<String> getShellCommandInternal(
                IsolatedExecutionContext executionContext) {
              return ImmutableList.<String>builder()
                  // We are now in the build-phase and can extract the path to the infer binary.
                  // Depending on configuration this will be either
                  // 1/ relative to a dotslash cache folder
                  // 2/ somewhere in buck-out where the infer 'toolchain' has been extracted.
                  //    this buck-out folder will work with RE since it part of the folder
                  //    that gets shipped as input to a RE job
                  .addAll(inferPlatform.getInferBin().getCommandPrefix(sourcePathResolver))
                  .add("capture")
                  .add("--buck")
                  .add(
                      "--results-dir",
                      toAbsNormalizedPath(
                              rootPath, outputPathResolver.resolvePath(resultDirectoryPath))
                          .toString())
                  .add("--project-root", rootPath.toString())
                  .add("--")
                  .add("clang")
                  .add("@" + toAbsNormalizedPath(rootPath, getArgsRelativePath(outputPathResolver)))
                  .build();
            }

            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
              // For buck-cell capture using remote-exection, we need to help infer find its config.
              Path inferconfigPath = Paths.get(buildCellRootPath.toString(), ".inferconfig");
              if (filesystem.exists(inferconfigPath)) {
                return ImmutableMap.of("INFERCONFIG", inferconfigPath.toString());
              } else {
                return ImmutableMap.of();
              }
            }

            @Override
            public String getShortName() {
              return "infer-capture";
            }
          });

      return ImmutableList.copyOf(steps.build());
    }

    private RelPath getArgsRelativePath(OutputPathResolver outputPathResolver) {
      RelPath outputPathParentDirectory = outputPathResolver.resolvePath(output).getParent();
      return outputPathParentDirectory.resolveRel("infer-capture.argsfile");
    }

    private RelPath getDepFilePath(OutputPathResolver outputPathResolver) {
      RelPath outputPath = outputPathResolver.resolvePath(output);
      RelPath outputPathParentDirectory = outputPath.getParent();
      return outputPathParentDirectory.resolveRel(outputPath.getFileName().toString() + ".dep");
    }

    public CompilerDelegate getCompilerDelegate() {
      return compilerDelegate;
    }

    private class WriteArgFileStep extends IsolatedStep {

      private final RelPath inputRelativePath;
      private final SourcePathResolverAdapter sourcePathResolver;
      private final OutputPathResolver outputPathResolver;

      private WriteArgFileStep(
          RelPath inputRelativePath,
          SourcePathResolverAdapter sourcePathResolver,
          OutputPathResolver outputPathResolver) {
        this.sourcePathResolver = sourcePathResolver;
        this.outputPathResolver = outputPathResolver;
        this.inputRelativePath = inputRelativePath;
      }

      @Override
      public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
          throws IOException {
        AbsPath ruleCellRoot = context.getRuleCellRoot();
        ProjectFilesystemUtils.writeLinesToPath(
            ruleCellRoot,
            Iterables.transform(getCompilerArgs(ruleCellRoot), Escaper.ARGFILE_ESCAPER::apply),
            getArgsRelativePath(outputPathResolver).getPath());
        return StepExecutionResults.SUCCESS;
      }

      @Override
      public String getShortName() {
        return "write-args-file";
      }

      @Override
      public String getIsolatedStepDescription(IsolatedExecutionContext context) {
        return "Write args file for clang";
      }

      private ImmutableList<String> getCompilerArgs(AbsPath ruleCellRoot) {
        ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

        ImmutableList<Arg> commandPrefixFlags =
            compilerDelegate.getCompiler().getCommandPrefix(sourcePathResolver).stream()
                .skip(1) // drop the binary
                .map(ArgFactory::from)
                .collect(ImmutableList.toImmutableList());

        return commandBuilder
            .add("-MD", "-MF", getDepFilePath(outputPathResolver).toString())
            .addAll(getPreIncludeArgs(ruleCellRoot))
            .addAll(
                Arg.stringify(
                    CxxToolFlags.concat(
                            CxxToolFlags.copyOf(
                                commandPrefixFlags, ImmutableList.of(), ImmutableList.of()),
                            preprocessorFlags,
                            preprocessorDelegate.getFlagsWithSearchPaths(
                                /* no precompiled headers */ Optional.empty(), sourcePathResolver),
                            compilerFlags)
                        .getAllFlags(),
                    sourcePathResolver))
            .add("-x", inputTypeLanguage)
            .add(
                "-o",
                // TODO(martinoluca): Use -fsyntax-only for better perf
                outputPathResolver.resolvePath(output).toString())
            .add("-c")
            .add(inputRelativePath.toString())
            .build();
      }

      private ImmutableList<String> getPreIncludeArgs(AbsPath ruleCellRoot) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        if (headerRelPath.isPresent()) {
          Preprocessor pp = preprocessorDelegate.getPreprocessor();
          builder.addAll(
              pp.prefixHeaderArgs(
                  toAbsNormalizedPath(ruleCellRoot, RelPath.get(headerRelPath.get())).getPath()));
        }
        return builder.build();
      }
    }
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().resultDirectoryPath);
  }

  @VisibleForTesting
  AbsPath getAbsolutePathToOutput() {
    ProjectFilesystem filesystem = getProjectFilesystem();
    OutputPathResolver outputPathResolver = getOutputPathResolver(filesystem);
    return filesystem.resolve(outputPathResolver.resolvePath(getBuildable().resultDirectoryPath));
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolverAdapter pathResolver) {
    return Depfiles.getCoveredByDepFilePredicate(
        Optional.of(getBuildable().preprocessorDelegate), Optional.empty());
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(
      SourcePathResolverAdapter pathResolver) {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    ProjectFilesystem filesystem = getProjectFilesystem();
    OutputPathResolver outputPathResolver = getOutputPathResolver(filesystem);
    Impl buildable = getBuildable();

    ImmutableList<Path> dependencies;
    try {
      dependencies =
          Depfiles.parseAndVerifyDependencies(
              context.getEventBus(),
              filesystem,
              sourcePathResolver,
              buildable.preprocessorDelegate.getHeaderPathNormalizer(context),
              HeaderVerification.of(HeaderVerification.Mode.IGNORE),
              buildable.getDepFilePath(outputPathResolver).getPath(),
              sourcePathResolver.getRelativePath(filesystem, buildable.input).getPath(),
              outputPathResolver.resolvePath(buildable.output).getPath(),
              DependencyTrackingMode.MAKEFILE,
              false);
    } catch (Depfiles.HeaderVerificationException e) {
      throw new HumanReadableException(e);
    }

    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // include all inputs coming from the preprocessor tool.
    inputs.addAll(
        buildable.preprocessorDelegate.getInputsAfterBuildingLocally(dependencies, context));

    // Add the input.
    inputs.add(buildable.input);

    return inputs.build();
  }

  private static AbsPath toAbsNormalizedPath(AbsPath root, RelPath relPath) {
    return root.resolve(relPath).normalize();
  }

  private OutputPathResolver getOutputPathResolver(ProjectFilesystem filesystem) {
    return new DefaultOutputPathResolver(filesystem, getBuildTarget());
  }
}
