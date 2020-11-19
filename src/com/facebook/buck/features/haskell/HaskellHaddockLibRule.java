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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HaskellHaddockLibRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final Logger LOG = Logger.get(HaskellHaddockLibRule.class);

  @AddToRuleKey private final Tool haddockTool;

  @AddToRuleKey private final ImmutableList<String> haddockFlags;

  @AddToRuleKey HaskellCompilerFlags compilerFlags;

  @AddToRuleKey ImmutableList<String> linkerFlags;

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey private final Preprocessor preprocessor;

  @AddToRuleKey HaskellPackageInfo packageInfo;
  private HaskellPlatform platform;

  @AddToRuleKey private final boolean withDownwardApi;

  @AddToRuleKey private final boolean skipSystemFrameworkSearchPaths;

  private HaskellHaddockLibRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      HaskellSources srcs,
      Tool haddockTool,
      ImmutableList<String> haddockFlags,
      HaskellCompilerFlags compilerFlags,
      ImmutableList<String> linkerFlags,
      HaskellPackageInfo packageInfo,
      HaskellPlatform platform,
      Preprocessor preprocessor,
      boolean withDownwardApi,
      boolean skipSystemFrameworkSearchPaths) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.srcs = srcs;
    this.haddockTool = haddockTool;
    this.haddockFlags = haddockFlags;
    this.compilerFlags = compilerFlags;
    this.linkerFlags = linkerFlags;
    this.packageInfo = packageInfo;
    this.platform = platform;
    this.preprocessor = preprocessor;
    this.withDownwardApi = withDownwardApi;
    this.skipSystemFrameworkSearchPaths = skipSystemFrameworkSearchPaths;
  }

  public static HaskellHaddockLibRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      HaskellSources sources,
      Tool haddockTool,
      ImmutableList<String> haddockFlags,
      HaskellCompilerFlags compilerFlags,
      ImmutableList<String> linkerFlags,
      HaskellPackageInfo packageInfo,
      HaskellPlatform platform,
      Preprocessor preprocessor,
      boolean withDownwardApi,
      boolean skipSystemFrameworkSearchPaths) {
    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(haddockTool, ruleFinder))
                    .addAll(sources.getDeps(ruleFinder))
                    .addAll(ruleFinder.filterBuildRuleInputs(compilerFlags.getHaddockInterfaces()))
                    .addAll(compilerFlags.getDeps(ruleFinder))
                    .build());
    return new HaskellHaddockLibRule(
        buildTarget,
        projectFilesystem,
        buildRuleParams.withDeclaredDeps(declaredDeps).withoutExtraDeps(),
        sources,
        haddockTool,
        haddockFlags,
        compilerFlags,
        linkerFlags,
        packageInfo,
        platform,
        preprocessor,
        withDownwardApi,
        skipSystemFrameworkSearchPaths);
  }

  private Path getObjectDir() {
    return getOutputDir().resolve("objects");
  }

  private Path getInterfaceDir() {
    return getOutputDir().resolve("interfaces");
  }

  /** @return the path where the compiler places generated FFI stub files. */
  private Path getStubDir() {
    return getOutputDir().resolve("stubs");
  }

  private Path getInterface() {
    String name = getBuildTarget().getShortName();
    return getOutputDir().resolve(name + "-haddock-interface");
  }

  private Path getHaddockOuptutDir() {
    return getOutputDir().resolve("ALL");
  }

  private Path getOutputDir() {
    RelPath p =
        BuildTargetPaths.getGenPath(getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s");
    // Haddock doesn't like commas in its file-paths for --read-interface
    // so replace commas with dashes
    return Paths.get(p.toString().replaceAll(",", "-"));
  }

  private AbsPath getArgsfile() {
    RelPath scratchDir =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
    return getProjectFilesystem().resolve(scratchDir).resolve("haddock.argsfile");
  }

  public ImmutableSet<SourcePath> getInterfaces() {
    SourcePath sp = ExplicitBuildTargetSourcePath.of(getBuildTarget(), getInterface());
    return ImmutableSet.of(sp);
  }

  public ImmutableSet<SourcePath> getHaddockOutputDirs() {
    SourcePath sp = ExplicitBuildTargetSourcePath.of(getBuildTarget(), getHaddockOuptutDir());
    return ImmutableSet.of(sp);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    String name = getBuildTarget().getShortName();
    Path dir = getOutputDir();

    LOG.verbose(name);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dir)));
    steps.add(new WriteArgsfileStep(context));
    steps.add(new HaddockStep(getProjectFilesystem().getRootPath(), withDownwardApi, context));

    buildableContext.recordArtifact(dir);
    return steps.build();
  }

  private Iterable<String> getPreprocessorFlags(SourcePathResolverAdapter resolver) {
    CxxToolFlags cxxToolFlags =
        compilerFlags
            .getPreprocessorFlags()
            .toToolFlags(
                resolver,
                PathShortener.identity(),
                CxxDescriptionEnhancer.frameworkPathToSearchPath(
                    platform.getCxxPlatform(), resolver, skipSystemFrameworkSearchPaths),
                preprocessor,
                /* pch */ Optional.empty());
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-optP"), Arg.stringify(cxxToolFlags.getAllFlags(), resolver));
  }

  private Iterable<String> getSourceArguments(SourcePathResolverAdapter resolver) {
    return srcs.getSourcePaths().stream()
        .map(resolver::getAbsolutePath)
        .map(Object::toString)
        .collect(Collectors.toList());
  }

  private class WriteArgsfileStep implements Step {

    private BuildContext buildContext;

    public WriteArgsfileStep(BuildContext buildContext) {
      this.buildContext = buildContext;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      getProjectFilesystem().createParentDirs(getArgsfile());
      getProjectFilesystem()
          .writeLinesToPath(
              Iterables.transform(
                  getSourceArguments(buildContext.getSourcePathResolver()),
                  Escaper.ARGFILE_ESCAPER::apply),
              getArgsfile().getPath());
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write-haddock-argsfile";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return "Write argsfile for haddock";
    }
  }

  private class HaddockStep extends IsolatedShellStep {

    private BuildContext buildContext;

    public HaddockStep(AbsPath rootPath, boolean withDownwardApi, BuildContext buildContext) {
      super(
          rootPath,
          ProjectFilesystemUtils.relativize(
              getProjectFilesystem().getRootPath(), buildContext.getBuildCellRootPath()),
          withDownwardApi);
      this.buildContext = buildContext;
    }

    @Override
    public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getEnvironmentVariables(platform))
          .putAll(haddockTool.getEnvironment(buildContext.getSourcePathResolver()))
          .build();
    }

    protected ImmutableList<String> getRenderFlags() {
      return ImmutableList.<String>builder()
          .add("--use-index", "doc-index.html")
          .add("--use-contents", "index.html")
          .add("--html")
          .add("--hoogle")
          .build();
    }

    protected ImmutableList<String> getOutputDirFlags() {
      ImmutableList.Builder<String> flags = ImmutableList.builder();
      flags.add("--odir", getProjectFilesystem().resolve(getHaddockOuptutDir()).toString());
      return flags.build();
    }

    @Override
    public boolean shouldPrintStderr(Verbosity verbosity) {
      return !verbosity.isSilent();
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

      ImmutableList.Builder<String> cmdArgs = ImmutableList.builder();

      // Haddock doesn't like RTS options, so strip them out.
      boolean isRTS = false;
      for (String s : compilerFlags.getAdditionalFlags()) {
        if (s.equals("+RTS")) {
          isRTS = true;
          continue;
        } else if (s.equals("-RTS")) {
          isRTS = false;
          continue;
        }
        if (isRTS) {
          continue;
        }
        cmdArgs.add(s);
      }

      cmdArgs.addAll(compilerFlags.getPackageFlags(platform, resolver));
      cmdArgs.addAll(linkerFlags);
      cmdArgs.addAll(getPreprocessorFlags(resolver));
      // Tell GHC where to place build files for TemplateHaskell
      cmdArgs.add("-odir", getProjectFilesystem().resolve(getObjectDir()).toString());
      cmdArgs.add("-hidir", getProjectFilesystem().resolve(getInterfaceDir()).toString());
      cmdArgs.add("-stubdir", getProjectFilesystem().resolve(getStubDir()).toString());

      String thisUnitId = String.format("%s-%s", packageInfo.getName(), packageInfo.getVersion());

      cmdArgs.add("-this-unit-id", thisUnitId);

      ImmutableList.Builder<String> builder = ImmutableList.builder();

      builder
          .addAll(haddockTool.getCommandPrefix(resolver))
          .addAll(getRenderFlags())
          .add("--no-tmp-comp-dir")
          .add("--no-warnings")
          .addAll(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("--read-interface"),
                  RichStream.from(compilerFlags.getHaddockInterfaces())
                      .map(sp -> resolver.getCellUnsafeRelPath(sp).toString())
                      .toImmutableList()))
          .add("--dump-interface", getInterface().toString())
          .addAll(haddockFlags)
          .addAll(MoreIterables.zipAndConcat(Iterables.cycle("--optghc"), cmdArgs.build()))
          .add("--package-name", packageInfo.getName())
          .add("--package-version", packageInfo.getVersion() + ".0")
          .addAll(getOutputDirFlags());

      if (platform.shouldUseArgsfile()) {
        builder.add("@" + getArgsfile());
      } else {
        builder.addAll(getSourceArguments(resolver));
      }

      return builder.build();
    }

    @Override
    public String getShortName() {
      return "haddock-lib-build";
    }
  }

  public HaskellPlatform getPlatform() {
    return platform;
  }
}
