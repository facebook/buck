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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
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
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

public class HaskellHaddockLibRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final Logger LOG = Logger.get(HaskellHaddockLibRule.class);

  @AddToRuleKey private final Tool haddockTool;

  @AddToRuleKey private final ImmutableList<String> haddockFlags;

  @AddToRuleKey ImmutableList<String> compilerFlags;

  @AddToRuleKey ImmutableList<String> linkerFlags;

  @AddToRuleKey private final PreprocessorFlags ppFlags;

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey private final Preprocessor preprocessor;

  @AddToRuleKey private final ImmutableSet<SourcePath> interfaces;

  @AddToRuleKey HaskellPackageInfo packageInfo;
  private HaskellPlatform platform;

  @AddToRuleKey final ImmutableSortedMap<String, HaskellPackage> packages;
  @AddToRuleKey final ImmutableSortedMap<String, HaskellPackage> exposedPackages;

  private HaskellHaddockLibRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      HaskellSources srcs,
      Tool haddockTool,
      ImmutableList<String> haddockFlags,
      ImmutableList<String> compilerFlags,
      ImmutableList<String> linkerFlags,
      ImmutableSet<SourcePath> interfaces,
      ImmutableSortedMap<String, HaskellPackage> packages,
      ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      HaskellPackageInfo packageInfo,
      HaskellPlatform platform,
      Preprocessor preprocessor,
      PreprocessorFlags ppFlags) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.srcs = srcs;
    this.haddockTool = haddockTool;
    this.haddockFlags = haddockFlags;
    this.compilerFlags = compilerFlags;
    this.linkerFlags = linkerFlags;
    this.interfaces = interfaces;
    this.packages = packages;
    this.exposedPackages = exposedPackages;
    this.packageInfo = packageInfo;
    this.platform = platform;
    this.preprocessor = preprocessor;
    this.ppFlags = ppFlags;
  }

  public static HaskellHaddockLibRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      HaskellSources sources,
      Tool haddockTool,
      ImmutableList<String> haddockFlags,
      ImmutableList<String> compilerFlags,
      ImmutableList<String> linkerFlags,
      ImmutableSet<SourcePath> interfaces,
      ImmutableSortedMap<String, HaskellPackage> packages,
      ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      HaskellPackageInfo packageInfo,
      HaskellPlatform platform,
      Preprocessor preprocessor,
      PreprocessorFlags ppFlags) {

    ImmutableList.Builder<BuildRule> pkgDeps = ImmutableList.builder();

    for (HaskellPackage pkg : packages.values()) {
      pkgDeps.addAll(pkg.getDeps(ruleFinder).iterator());
    }
    for (HaskellPackage pkg : exposedPackages.values()) {
      pkgDeps.addAll(pkg.getDeps(ruleFinder).iterator());
    }

    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(haddockTool, ruleFinder))
                    .addAll(sources.getDeps(ruleFinder))
                    .addAll(ruleFinder.filterBuildRuleInputs(interfaces))
                    .addAll(pkgDeps.build())
                    .addAll(ppFlags.getDeps(ruleFinder))
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
        interfaces,
        packages,
        exposedPackages,
        packageInfo,
        platform,
        preprocessor,
        ppFlags);
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
    Path p = BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
    // Haddock doesn't like commas in its file-paths for --read-interface
    // so replace commas with dashes
    return Paths.get(p.toString().replaceAll(",", "-"));
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
    steps.add(new HaddockStep(getProjectFilesystem().getRootPath(), context));

    buildableContext.recordArtifact(dir);
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
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-optP"), Arg.stringify(cxxToolFlags.getAllFlags(), resolver));
  }

  private class HaddockStep extends ShellStep {

    private BuildContext buildContext;

    public HaddockStep(Path rootPath, BuildContext buildContext) {
      super(rootPath);
      this.buildContext = buildContext;
    }

    @Override
    public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getEnvironmentVariables(context))
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
    protected boolean shouldPrintStderr(Verbosity verbosity) {
      return !verbosity.isSilent();
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      SourcePathResolver resolver = buildContext.getSourcePathResolver();

      ImmutableList.Builder<String> cmdArgs = ImmutableList.builder();

      // Haddock doesn't like RTS options, so strip them out.
      boolean isRTS = false;
      for (String s : compilerFlags) {
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

      ImmutableSet.Builder<String> dbBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> exposeBuilder = ImmutableSet.builder();
      for (HaskellPackage pkg : packages.values()) {
        dbBuilder.add(resolver.getRelativePath(pkg.getPackageDb()).toString());
      }
      for (HaskellPackage pkg : exposedPackages.values()) {
        dbBuilder.add(resolver.getRelativePath(pkg.getPackageDb()).toString());
        exposeBuilder.add(
            String.format("%s-%s", pkg.getInfo().getName(), pkg.getInfo().getVersion()));
      }
      cmdArgs.addAll(MoreIterables.zipAndConcat(Iterables.cycle("-package-db"), dbBuilder.build()));
      cmdArgs.addAll(
          MoreIterables.zipAndConcat(Iterables.cycle("-expose-package"), exposeBuilder.build()));
      cmdArgs.addAll(linkerFlags);
      cmdArgs.addAll(getPreprocessorFlags(resolver));
      // Tell GHC where to place build files for TemplateHaskell
      cmdArgs.add("-odir", getProjectFilesystem().resolve(getObjectDir()).toString());
      cmdArgs.add("-hidir", getProjectFilesystem().resolve(getInterfaceDir()).toString());
      cmdArgs.add("-stubdir", getProjectFilesystem().resolve(getStubDir()).toString());

      return ImmutableList.<String>builder()
          .addAll(haddockTool.getCommandPrefix(resolver))
          .addAll(getRenderFlags())
          .add("--no-tmp-comp-dir")
          .add("--no-warnings")
          .addAll(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("--read-interface"),
                  RichStream.from(interfaces)
                      .map(sp -> resolver.getRelativePath(sp).toString())
                      .toImmutableList()))
          .add("--dump-interface", getInterface().toString())
          .addAll(haddockFlags)
          .addAll(MoreIterables.zipAndConcat(Iterables.cycle("--optghc"), cmdArgs.build()))
          .add("--package-name", packageInfo.getName())
          .add("--package-version", packageInfo.getVersion() + ".0")
          .addAll(
              srcs.getSourcePaths()
                  .stream()
                  .map(resolver::getRelativePath)
                  .map(Object::toString)
                  .iterator())
          .addAll(getOutputDirFlags())
          .build();
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
