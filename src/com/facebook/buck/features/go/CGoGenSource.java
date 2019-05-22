/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.Collectors;

public class CGoGenSource extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableSet<SourcePath> cgoSrcs;
  @AddToRuleKey private final Tool cgo;
  @AddToRuleKey private final GoPlatform platform;
  @AddToRuleKey private final ImmutableList<String> cgoCompilerFlags;
  @AddToRuleKey private final PreprocessorFlags ppFlags;
  @AddToRuleKey private final Preprocessor preprocessor;

  @AddToRuleKey
  private final ImmutableMap<String, NonHashableSourcePathContainer> headerLinkTreeMap;

  private final ImmutableSortedSet<BuildRule> buildDeps;
  private final Path genDir;
  private final Path argsFile;
  private final ImmutableList<SourcePath> cFiles;
  private final ImmutableList<SourcePath> cgoFiles;
  private final ImmutableList<SourcePath> goFiles;
  private final ImmutableList<Path> includeDirs;
  private final Preprocessor cpp;

  public CGoGenSource(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Preprocessor preprocessor,
      ImmutableSet<SourcePath> cgoSrcs,
      HeaderSymlinkTree headerSymlinkTree,
      Tool cgo,
      ImmutableList<String> cgoCompilerFlags,
      PreprocessorFlags ppFlags,
      Preprocessor cpp,
      GoPlatform platform) {
    super(buildTarget, projectFilesystem);
    this.cgoSrcs = cgoSrcs;
    this.cgo = cgo;
    this.cgoCompilerFlags = cgoCompilerFlags;
    this.platform = platform;
    this.preprocessor = preprocessor;
    this.genDir = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/gen/");
    this.argsFile = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/cgo.argsfile");
    this.ppFlags = ppFlags;
    this.headerLinkTreeMap =
        headerSymlinkTree.getLinks().entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    e -> e.getKey().toString(),
                    e -> new NonHashableSourcePathContainer(e.getValue())));

    ImmutableList.Builder<SourcePath> cBuilder = ImmutableList.builder();
    ImmutableList.Builder<SourcePath> cgoBuilder = ImmutableList.builder();
    ImmutableList.Builder<SourcePath> goBuilder = ImmutableList.builder();

    for (SourcePath srcPath : cgoSrcs) {
      String filename =
          ruleFinder.getSourcePathResolver().getAbsolutePath(srcPath).getFileName().toString();
      String filenameWithoutExt =
          filename.substring(0, filename.lastIndexOf('.')).replace(File.separatorChar, '_');

      // cgo generates 2 files for each Go sources, 1 .cgo1.go and 1 .cgo2.c
      goBuilder.add(
          ExplicitBuildTargetSourcePath.of(
              buildTarget, genDir.resolve(filenameWithoutExt + ".cgo1.go")));
      cBuilder.add(
          ExplicitBuildTargetSourcePath.of(
              buildTarget, genDir.resolve(filenameWithoutExt + ".cgo2.c")));
    }

    cBuilder.add(ExplicitBuildTargetSourcePath.of(buildTarget, genDir.resolve("_cgo_export.c")));
    cgoBuilder.add(ExplicitBuildTargetSourcePath.of(buildTarget, genDir.resolve("_cgo_main.c")));

    goBuilder.add(ExplicitBuildTargetSourcePath.of(buildTarget, genDir.resolve("_cgo_gotypes.go")));

    this.cFiles = cBuilder.build();
    this.cgoFiles = cgoBuilder.build();
    this.goFiles = goBuilder.build();
    this.includeDirs = ImmutableList.of(headerSymlinkTree.getRoot());
    this.cpp = cpp;

    this.buildDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(BuildableSupport.getDepsCollection(cgo, ruleFinder))
            .addAll(BuildableSupport.getDepsCollection(cpp, ruleFinder))
            .addAll(ppFlags.getDeps(ruleFinder))
            .addAll(ruleFinder.filterBuildRuleInputs(cgoSrcs))
            .add(headerSymlinkTree)
            .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDir)));
    steps.add(new WriteArgsfileStep(context));
    steps.add(
        new CGoCompileStep(
            getProjectFilesystem().getRootPath(),
            cgo.getEnvironment(context.getSourcePathResolver()),
            cgo.getCommandPrefix(context.getSourcePathResolver()),
            cpp.getCommandPrefix(context.getSourcePathResolver()),
            cgoCompilerFlags,
            ImmutableList.of("@" + argsFile),
            cgoSrcs.stream()
                .map(context.getSourcePathResolver()::getRelativePath)
                .collect(ImmutableList.toImmutableList()),
            platform,
            genDir));

    buildableContext.recordArtifact(genDir);
    return steps.build();
  }

  private Iterable<String> getPreprocessorFlags(SourcePathResolver resolver) {
    CxxToolFlags cxxToolFlags =
        ppFlags.toToolFlags(
            resolver,
            PathShortener.identity(),
            CxxDescriptionEnhancer.frameworkPathToSearchPath(platform.getCxxPlatform(), resolver),
            preprocessor,
            /* pch */ Optional.empty());
    return MoreIterables.zipAndConcat(Arg.stringify(cxxToolFlags.getAllFlags(), resolver));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), genDir);
  }

  public ImmutableList<SourcePath> getCFiles() {
    return cFiles;
  }

  public ImmutableList<SourcePath> getCgoFiles() {
    return cgoFiles;
  }

  public ImmutableList<SourcePath> getGoFiles() {
    return goFiles;
  }

  public SourcePath getExportHeader() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), genDir.resolve("_cgo_export.h"));
  }

  private class WriteArgsfileStep implements Step {

    private BuildContext buildContext;

    public WriteArgsfileStep(BuildContext buildContext) {
      this.buildContext = buildContext;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      getProjectFilesystem().createParentDirs(argsFile);
      getProjectFilesystem()
          .writeLinesToPath(
              Iterables.transform(
                  new ImmutableList.Builder<String>()
                      .addAll(getPreprocessorFlags(buildContext.getSourcePathResolver()))
                      .addAll(
                          includeDirs.stream()
                              .map(dir -> "-I" + dir.toString())
                              .collect(Collectors.toSet()))
                      .build(),
                  Escaper.ARGFILE_ESCAPER::apply),
              argsFile);

      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write-cgo-argsfile";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "Write argsfile for cgo";
    }
  }
}
